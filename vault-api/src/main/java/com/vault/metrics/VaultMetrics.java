package com.vault.metrics;

import com.vault.metadata.NodeStatus;
import com.vault.service.NodeService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Central place for Prometheus metrics (exposed at /actuator/prometheus). */
@Component
public class VaultMetrics {

    public final Counter uploads;
    public final Counter downloads;
    public final Counter deletes;
    public final Counter updates;
    public final Counter versionConflicts;
    public final Counter repairs;
    public final Counter repairFailures;
    public final Counter corruptionsDetected;
    public final Counter missingDetected;
    public final Counter nodeFailures;
    public final Counter rebalances;
    public final Counter repairsPausedByPartitionGuard;
    public final Timer uploadLatency;
    public final Timer downloadLatency;
    public final Timer repairDuration;
    public final Timer rebalanceDuration;

    private final MeterRegistry registry;
    private final NodeService nodes;
    private final Set<String> registeredNodeGauges = ConcurrentHashMap.newKeySet();

    public VaultMetrics(MeterRegistry registry, NodeService nodes) {
        this.registry = registry;
        this.nodes = nodes;
        uploads = Counter.builder("vault.upload").description("Objects uploaded").register(registry);
        downloads = Counter.builder("vault.download").description("Objects downloaded").register(registry);
        deletes = Counter.builder("vault.delete").description("Objects deleted").register(registry);
        updates = Counter.builder("vault.update").description("Object updates committed").register(registry);
        versionConflicts = Counter.builder("vault.version.conflict").register(registry);
        repairs = Counter.builder("vault.repair").description("Replicas repaired or re-created").register(registry);
        repairFailures = Counter.builder("vault.repair.failure").register(registry);
        corruptionsDetected = Counter.builder("vault.corruption").description("Corrupted replicas detected").register(registry);
        missingDetected = Counter.builder("vault.replica.missing").register(registry);
        nodeFailures = Counter.builder("vault.node.failure").description("Nodes declared UNHEALTHY").register(registry);
        rebalances = Counter.builder("vault.rebalance").description("Replicas moved by the rebalancer").register(registry);
        repairsPausedByPartitionGuard = Counter.builder("vault.repair.paused.partition").register(registry);
        uploadLatency = Timer.builder("vault.upload.latency").publishPercentileHistogram().register(registry);
        downloadLatency = Timer.builder("vault.download.latency").publishPercentileHistogram().register(registry);
        repairDuration = Timer.builder("vault.repair.duration").register(registry);
        rebalanceDuration = Timer.builder("vault.rebalance.duration").register(registry);

        for (NodeStatus status : NodeStatus.values()) {
            Gauge.builder("vault.nodes", () -> nodes.countByStatus().getOrDefault(status, 0L))
                    .tag("status", status.name().toLowerCase())
                    .register(registry);
        }
    }

    /** Registers the per-node storage utilization gauge the first time a node is seen. */
    public void trackNode(String nodeId) {
        if (registeredNodeGauges.add(nodeId)) {
            Gauge.builder("vault.storage.usage.ratio", () -> nodes.utilizationOf(nodeId))
                    .tag("node", nodeId)
                    .register(registry);
        }
    }
}
