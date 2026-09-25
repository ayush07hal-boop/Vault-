package com.vault.repair;

import com.vault.events.VaultEvent;
import com.vault.events.VaultEventPublisher;
import com.vault.metadata.MetadataService;
import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ObjectStatus;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaStatus;
import com.vault.metadata.StorageNodeEntity;
import com.vault.metrics.VaultMetrics;
import com.vault.replication.ObjectLocks;
import com.vault.replication.PlacementService;
import com.vault.service.NodeService;
import com.vault.storage.NodeRpcException;
import com.vault.storage.StorageNodeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;

/**
 * Brings one object back to its desired state: {@code replicationFactor} verified replicas of the
 * current version on healthy nodes. The same routine handles lost nodes, corrupted or missing files,
 * outdated versions and over-replication, always following: copy -> verify checksum -> update metadata -> only then delete.
 */
@Service
public class RepairService {

    private static final Logger log = LoggerFactory.getLogger(RepairService.class);

    public enum Outcome {
        /** Object is gone or not active. */
        SKIPPED,
        /** Nothing to do. */
        HEALTHY,
        /** Repaired; now fully replicated. */
        REPAIRED,
        /** Progress made or attempted, but still short (no target/source available); will be retried. */
        PARTIAL,
        /** No verified copy is currently reachable, so nothing can be repaired from. */
        NO_SOURCE
    }

    private final MetadataService metadata;
    private final NodeService nodeService;
    private final PlacementService placement;
    private final StorageNodeClient client;
    private final ReplicaGarbageCollector garbageCollector;
    private final ObjectLocks locks;
    private final VaultEventPublisher events;
    private final VaultMetrics metrics;

    public RepairService(MetadataService metadata, NodeService nodeService, PlacementService placement,
                         StorageNodeClient client, ReplicaGarbageCollector garbageCollector, ObjectLocks locks,
                         VaultEventPublisher events, VaultMetrics metrics) {
        this.metadata = metadata;
        this.nodeService = nodeService;
        this.placement = placement;
        this.client = client;
        this.garbageCollector = garbageCollector;
        this.locks = locks;
        this.events = events;
        this.metrics = metrics;
    }

    public Outcome repairObject(String objectId) {
        Lock lock = locks.lockFor(objectId);
        lock.lock();
        long started = System.nanoTime();
        try {
            Outcome outcome = doRepair(objectId);
            if (outcome == Outcome.REPAIRED) {
                metrics.repairDuration.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
            return outcome;
        } finally {
            lock.unlock();
        }
    }

    private Outcome doRepair(String objectId) {
        ObjectEntity object = metadata.find(objectId).filter(o -> o.getStatus() == ObjectStatus.ACTIVE).orElse(null);
        if (object == null) {
            return Outcome.SKIPPED;
        }
        long version = object.getVersion();
        int rf = object.getReplicationFactor();
        Map<String, StorageNodeEntity> nodes = nodeService.byId();
        List<ReplicaEntity> rows = metadata.replicasOf(objectId);

        // Verified copies we can read from right now.
        List<ReplicaEntity> good = rows.stream()
                .filter(r -> r.getStatus() == ReplicaStatus.HEALTHY && r.getVersion() == version)
                .filter(r -> reachable(nodes.get(r.getNodeId())))
                .sorted(Comparator.comparingDouble(r -> nodes.get(r.getNodeId()).utilization()))
                .toList();
        // Rows that exist but are unusable; they are repaired in place on their (live) node.
        List<ReplicaEntity> broken = rows.stream()
                .filter(r -> ReplicaStatus.NEEDS_REPAIR.contains(r.getStatus())
                        || (r.getStatus() == ReplicaStatus.HEALTHY && r.getVersion() != version))
                .toList();

        if (good.isEmpty()) {
            if (!rows.isEmpty()) {
                log.error("object {} has no readable verified replica - cannot repair until one comes back", objectId);
            }
            return Outcome.NO_SOURCE;
        }

        boolean changed = false;
        boolean complete = true;
        int usable = good.size();

        for (ReplicaEntity row : broken) {
            StorageNodeEntity target = nodes.get(row.getNodeId());
            if (target == null || target.getStatus() != NodeStatus.HEALTHY) {
                complete = false; // node itself is down; the copy will be re-created elsewhere below
                continue;
            }
            if (copyReplica(object, target, good, nodes, row)) {
                usable++;
                changed = true;
            } else {
                complete = false;
            }
        }

        if (usable < rf) {
            Set<String> exclude = new HashSet<>();
            rows.forEach(r -> exclude.add(r.getNodeId()));
            List<StorageNodeEntity> targets = placement.choose(rf - usable, object.getSizeBytes(), exclude);
            for (StorageNodeEntity target : targets) {
                if (copyReplica(object, target, good, nodes, null)) {
                    usable++;
                    changed = true;
                }
            }
            if (usable < rf) {
                complete = false;
                log.warn("object {} still has {}/{} usable replicas after repair pass", objectId, usable, rf);
            }
        } else if (usable > rf && !changed) {
            trim(good, usable - rf, nodes);
            changed = true;
        }

        if (!complete) {
            return Outcome.PARTIAL;
        }
        return changed ? Outcome.REPAIRED : Outcome.HEALTHY;
    }

    /**
     * Copies the current version onto {@code target} from the first source that works.
     * {@code existing} is the broken row being repaired in place (null when creating a fresh replica).
     */
    private boolean copyReplica(ObjectEntity object, StorageNodeEntity target, List<ReplicaEntity> sources,
                                Map<String, StorageNodeEntity> nodes, ReplicaEntity existing) {
        String objectId = object.getObjectId();
        long version = object.getVersion();
        for (ReplicaEntity source : sources) {
            StorageNodeEntity sourceNode = nodes.get(source.getNodeId());
            if (sourceNode.getNodeId().equals(target.getNodeId())) {
                continue;
            }
            try {
                client.copy(target, sourceNode, objectId, version, object.getChecksum());
            } catch (NodeRpcException e) {
                switch (e.getKind()) {
                    case CHECKSUM_MISMATCH -> {
                        // The target hashed what the source sent and it was wrong: the source is corrupt.
                        log.error("source {} of {} sent bytes that fail the checksum", source.getNodeId(), objectId);
                        if (metadata.markReplica(objectId, source.getNodeId(), source.getVersion(), ReplicaStatus.CORRUPTED)) {
                            metrics.corruptionsDetected.increment();
                            events.publish(new VaultEvent.ReplicaCorrupted(objectId, source.getNodeId()));
                        }
                    }
                    case NOT_FOUND -> {
                        if (metadata.markReplica(objectId, source.getNodeId(), source.getVersion(), ReplicaStatus.MISSING)) {
                            metrics.missingDetected.increment();
                            events.publish(new VaultEvent.ReplicaMissing(objectId, source.getNodeId()));
                        }
                    }
                    case NO_SPACE -> {
                        log.warn("target {} is out of space while repairing {}", target.getNodeId(), objectId);
                        metrics.repairFailures.increment();
                        return false;
                    }
                    default -> log.warn("copy {} {} -> {} failed: {}", objectId, source.getNodeId(), target.getNodeId(),
                            e.getMessage());
                }
                continue; // try the next source
            }

            if (!metadata.commitRepairedReplica(objectId, target.getNodeId(), version, object.getChecksum())) {
                // The object was updated or deleted while we copied: this copy is already stale.
                garbageCollector.deleteFileQuietly(target, objectId, version);
                return false;
            }
            if (existing != null && existing.getVersion() != version) {
                garbageCollector.deleteFileQuietly(target, objectId, existing.getVersion());
            }
            metrics.repairs.increment();
            log.info("repaired {} v{}: {} -> {}", objectId, version, source.getNodeId(), target.getNodeId());
            return true;
        }
        metrics.repairFailures.increment();
        return false;
    }

    /** Over-replicated (e.g. a node came back after its replicas were re-created): drop the surplus. */
    private void trim(List<ReplicaEntity> good, int surplus, Map<String, StorageNodeEntity> nodes) {
        List<ReplicaEntity> byLoad = new ArrayList<>(good);
        byLoad.sort(Comparator.comparingDouble((ReplicaEntity r) -> nodes.get(r.getNodeId()).utilization()).reversed());
        for (ReplicaEntity victim : byLoad.subList(0, Math.min(surplus, byLoad.size()))) {
            if (metadata.markReplica(victim.getObjectId(), victim.getNodeId(), victim.getVersion(),
                    ReplicaStatus.PENDING_DELETE)) {
                garbageCollector.deleteNow(victim);
                log.info("trimmed surplus replica of {} on {}", victim.getObjectId(), victim.getNodeId());
            }
        }
    }

    private static boolean reachable(StorageNodeEntity node) {
        return node != null && NodeStatus.REACHABLE.contains(node.getStatus());
    }
}
