package com.vault.integrity;

import com.vault.config.VaultProperties;
import com.vault.events.VaultEvent;
import com.vault.events.VaultEventPublisher;
import com.vault.metadata.MetadataService;
import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ObjectStatus;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaRepository;
import com.vault.metadata.ReplicaStatus;
import com.vault.metadata.StorageNodeEntity;
import com.vault.metrics.VaultMetrics;
import com.vault.service.NodeService;
import com.vault.storage.NodeRpcException;
import com.vault.storage.StorageNodeClient;
import com.vault.storage.StorageNodeClient.VerifyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Background scrubber: asks nodes to re-read replicas from disk and recompute SHA-256, catching silent
 * corruption (bit rot, manual tampering, truncated files) before a client ever reads the bad copy.
 * Each pass takes the least-recently verified replicas, so over time everything is covered.
 */
@Component
public class IntegrityVerifier {

    private static final Logger log = LoggerFactory.getLogger(IntegrityVerifier.class);

    private final ReplicaRepository replicas;
    private final MetadataService metadata;
    private final NodeService nodeService;
    private final StorageNodeClient client;
    private final VaultEventPublisher events;
    private final VaultMetrics metrics;
    private final VaultProperties props;

    public IntegrityVerifier(ReplicaRepository replicas, MetadataService metadata, NodeService nodeService,
                             StorageNodeClient client, VaultEventPublisher events, VaultMetrics metrics,
                             VaultProperties props) {
        this.replicas = replicas;
        this.metadata = metadata;
        this.nodeService = nodeService;
        this.client = client;
        this.events = events;
        this.metrics = metrics;
        this.props = props;
    }

    public record Report(int checked, int corrupted, int missing) {
    }

    @Scheduled(fixedDelayString = "${vault.integrity.interval-seconds:3600}",
            initialDelayString = "${vault.integrity.interval-seconds:3600}", timeUnit = TimeUnit.SECONDS)
    void scheduledVerify() {
        if (!props.integrity().enabled()) {
            return;
        }
        try {
            Report r = verifyBatch();
            log.info("integrity pass: checked={} corrupted={} missing={}", r.checked(), r.corrupted(), r.missing());
        } catch (Exception e) {
            log.error("integrity pass failed", e);
        }
    }

    public Report verifyBatch() {
        List<ReplicaEntity> batch = replicas.findVerificationCandidates(PageRequest.of(0, props.integrity().batchSize()));
        Map<String, StorageNodeEntity> nodes = nodeService.byId();
        int checked = 0;
        int corrupted = 0;
        int missing = 0;
        for (ReplicaEntity replica : batch) {
            StorageNodeEntity node = nodes.get(replica.getNodeId());
            if (node == null || node.getStatus() != NodeStatus.HEALTHY) {
                continue;
            }
            Optional<ObjectEntity> object = metadata.find(replica.getObjectId());
            if (object.isEmpty() || object.get().getStatus() != ObjectStatus.ACTIVE
                    || object.get().getVersion() != replica.getVersion()) {
                continue; // stale row; repair/GC will deal with it
            }
            try {
                VerifyResult result = client.verify(node, replica.getObjectId(), replica.getVersion());
                checked++;
                if (!result.exists()) {
                    // guarded by version: ignored if an update committed while we were verifying
                    if (metadata.markReplica(replica.getObjectId(), replica.getNodeId(), replica.getVersion(),
                            ReplicaStatus.MISSING)) {
                        missing++;
                        metrics.missingDetected.increment();
                        events.publish(new VaultEvent.ReplicaMissing(replica.getObjectId(), replica.getNodeId()));
                    }
                } else if (!result.checksum().equalsIgnoreCase(replica.getChecksum())) {
                    if (metadata.markReplica(replica.getObjectId(), replica.getNodeId(), replica.getVersion(),
                            ReplicaStatus.CORRUPTED)) {
                        corrupted++;
                        metrics.corruptionsDetected.increment();
                        log.error("CORRUPTION: {} on {} expected {} found {}", replica.getObjectId(),
                                replica.getNodeId(), replica.getChecksum(), result.checksum());
                        events.publish(new VaultEvent.ReplicaCorrupted(replica.getObjectId(), replica.getNodeId()));
                    }
                } else {
                    metadata.markVerified(replica.getObjectId(), replica.getNodeId(), replica.getVersion());
                }
            } catch (NodeRpcException e) {
                if (e.getKind() == NodeRpcException.Kind.UNREACHABLE) {
                    nodeService.suspect(node.getNodeId());
                }
                log.warn("could not verify {} on {}: {}", replica.getObjectId(), node.getNodeId(), e.getMessage());
            }
        }
        return new Report(checked, corrupted, missing);
    }
}
