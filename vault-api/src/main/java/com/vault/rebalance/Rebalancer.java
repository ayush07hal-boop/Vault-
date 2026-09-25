package com.vault.rebalance;

import com.vault.config.VaultProperties;
import com.vault.metadata.MetadataService;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ObjectStatus;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaRepository;
import com.vault.metadata.ReplicaStatus;
import com.vault.metadata.StorageNodeEntity;
import com.vault.metrics.VaultMetrics;
import com.vault.repair.PartitionGuard;
import com.vault.repair.ReplicaGarbageCollector;
import com.vault.replication.ObjectLocks;
import com.vault.service.NodeService;
import com.vault.storage.NodeRpcException;
import com.vault.storage.StorageNodeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Moves replicas off nodes that are too full onto the emptiest node that does not already hold the object.
 * Each move is copy -> verify (done by the target) -> metadata commit -> only then delete the old copy,
 * so a crash at any point leaves at least the original replicas intact.
 */
@Component
public class Rebalancer {

    private static final Logger log = LoggerFactory.getLogger(Rebalancer.class);

    private final NodeService nodeService;
    private final ReplicaRepository replicas;
    private final MetadataService metadata;
    private final StorageNodeClient client;
    private final ReplicaGarbageCollector garbageCollector;
    private final PartitionGuard guard;
    private final ObjectLocks locks;
    private final VaultMetrics metrics;
    private final VaultProperties props;

    public Rebalancer(NodeService nodeService, ReplicaRepository replicas, MetadataService metadata,
                      StorageNodeClient client, ReplicaGarbageCollector garbageCollector, PartitionGuard guard,
                      ObjectLocks locks, VaultMetrics metrics, VaultProperties props) {
        this.nodeService = nodeService;
        this.replicas = replicas;
        this.metadata = metadata;
        this.client = client;
        this.garbageCollector = garbageCollector;
        this.guard = guard;
        this.locks = locks;
        this.metrics = metrics;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${vault.rebalance.interval-seconds:300}",
            initialDelayString = "${vault.rebalance.interval-seconds:300}", timeUnit = TimeUnit.SECONDS)
    void scheduledRebalance() {
        if (!props.rebalance().enabled()) {
            return;
        }
        try {
            int moved = runOnce();
            if (moved > 0) {
                log.info("rebalance pass moved {} replica(s)", moved);
            }
        } catch (Exception e) {
            log.error("rebalance pass failed", e);
        }
    }

    /** @return number of replicas moved in this pass */
    public int runOnce() {
        if (guard.isTripped()) {
            return 0; // never shuffle data while the cluster looks partitioned
        }
        long started = System.nanoTime();
        List<StorageNodeEntity> healthy = nodeService.healthy();
        if (healthy.size() < 2) {
            return 0;
        }
        // Local view of usage that we adjust as we move data, since heartbeats only refresh it periodically.
        Map<String, Long> used = new HashMap<>();
        healthy.forEach(n -> used.put(n.getNodeId(), n.getUsedCapacity()));

        int moves = 0;
        while (moves < props.rebalance().maxMovesPerRun()) {
            StorageNodeEntity source = healthy.stream()
                    .max(Comparator.comparingDouble(n -> util(n, used))).orElseThrow();
            StorageNodeEntity target = healthy.stream()
                    .min(Comparator.comparingDouble(n -> util(n, used))).orElseThrow();
            double sourceUtil = util(source, used);
            double targetUtil = util(target, used);
            if (sourceUtil * 100 <= props.rebalance().thresholdPercent()
                    || (sourceUtil - targetUtil) * 100 < props.rebalance().minImprovementPercent()) {
                break;
            }
            long movedBytes = moveOneObject(source, target, used);
            if (movedBytes < 0) {
                break; // nothing movable
            }
            used.merge(source.getNodeId(), -movedBytes, Long::sum);
            used.merge(target.getNodeId(), movedBytes, Long::sum);
            moves++;
        }
        if (moves > 0) {
            metrics.rebalanceDuration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
        return moves;
    }

    private static double util(StorageNodeEntity node, Map<String, Long> used) {
        return node.getTotalCapacity() <= 0 ? 1.0 : (double) used.get(node.getNodeId()) / node.getTotalCapacity();
    }

    /** @return bytes moved, or -1 if no object on {@code source} could be moved to {@code target} */
    private long moveOneObject(StorageNodeEntity source, StorageNodeEntity target, Map<String, Long> used) {
        List<ReplicaEntity> candidates = replicas.findHealthyOnNode(source.getNodeId(), PageRequest.of(0, 100));
        for (ReplicaEntity replica : candidates) {
            Lock lock = locks.lockFor(replica.getObjectId());
            lock.lock();
            try {
                Optional<ObjectEntity> found = metadata.find(replica.getObjectId());
                if (found.isEmpty() || found.get().getStatus() != ObjectStatus.ACTIVE
                        || found.get().getVersion() != replica.getVersion()) {
                    continue;
                }
                ObjectEntity object = found.get();
                long size = object.getSizeBytes();
                boolean targetHolds = metadata.replicasOf(object.getObjectId()).stream()
                        .anyMatch(r -> r.getNodeId().equals(target.getNodeId()));
                long targetUsedAfter = used.get(target.getNodeId()) + size;
                long sourceUsedAfter = used.get(source.getNodeId()) - size;
                double targetUtilAfter = (double) targetUsedAfter / target.getTotalCapacity();
                double sourceUtilAfter = (double) sourceUsedAfter / source.getTotalCapacity();
                // Only move if the target has room and does not end up fuller than the source (prevents ping-pong).
                if (targetHolds || targetUsedAfter > target.getTotalCapacity() || targetUtilAfter > sourceUtilAfter) {
                    continue;
                }
                if (move(object, replica, source, target)) {
                    return size;
                }
            } finally {
                lock.unlock();
            }
        }
        return -1;
    }

    private boolean move(ObjectEntity object, ReplicaEntity replica, StorageNodeEntity source, StorageNodeEntity target) {
        String objectId = object.getObjectId();
        long version = object.getVersion();
        try {
            client.copy(target, source, objectId, version, object.getChecksum()); // target verifies the SHA-256
        } catch (NodeRpcException e) {
            log.warn("rebalance copy of {} {} -> {} failed: {}", objectId, source.getNodeId(), target.getNodeId(),
                    e.getMessage());
            return false;
        }
        if (!metadata.commitMove(objectId, source.getNodeId(), target.getNodeId(), version, object.getChecksum())) {
            garbageCollector.deleteFileQuietly(target, objectId, version); // object changed underneath us
            return false;
        }
        // Metadata now points at the new copy; the old row is PENDING_DELETE, so removing the file is safe.
        replicas.findByObjectIdAndNodeId(objectId, source.getNodeId())
                .filter(r -> r.getStatus() == ReplicaStatus.PENDING_DELETE)
                .ifPresent(garbageCollector::deleteNow);
        metrics.rebalances.increment();
        log.info("rebalanced {} v{}: {} -> {}", objectId, version, source.getNodeId(), target.getNodeId());
        return true;
    }
}
