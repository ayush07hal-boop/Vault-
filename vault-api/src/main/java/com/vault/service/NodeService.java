package com.vault.service;

import com.vault.config.VaultProperties;
import com.vault.events.VaultEvent;
import com.vault.events.VaultEventPublisher;
import com.vault.health.NodeStateMachine;
import com.vault.health.NodeStateMachine.State;
import com.vault.metadata.NodeStatus;
import com.vault.metadata.StorageNodeEntity;
import com.vault.metadata.StorageNodeRepository;
import com.vault.storage.StorageNodeClient.NodeHealthReport;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Node registry plus the health state transitions driven by heartbeats and RPC outcomes. */
@Service
public class NodeService {

    private static final Logger log = LoggerFactory.getLogger(NodeService.class);

    private final StorageNodeRepository nodes;
    private final VaultProperties props;
    private final VaultEventPublisher events;
    /** Latest utilization per node for the Prometheus gauges (avoids hitting the DB on every scrape). */
    private final Map<String, Double> utilization = new ConcurrentHashMap<>();

    public NodeService(StorageNodeRepository nodes, VaultProperties props, VaultEventPublisher events) {
        this.nodes = nodes;
        this.props = props;
        this.events = events;
    }

    /** Registers nodes from configuration. Existing rows keep their status and just pick up address changes. */
    @PostConstruct
    void seed() {
        for (VaultProperties.NodeSpec spec : props.nodes()) {
            StorageNodeEntity node = nodes.findById(spec.id())
                    .orElseGet(() -> new StorageNodeEntity(spec.id(), spec.host(), spec.port(), spec.zone()));
            node.setAddress(spec.host());
            node.setPort(spec.port());
            node.setZone(spec.zone());
            nodes.save(node);
            log.info("registered storage node {} at {}:{}", spec.id(), spec.host(), spec.port());
        }
    }

    public List<StorageNodeEntity> all() {
        return nodes.findAll();
    }

    public Optional<StorageNodeEntity> find(String nodeId) {
        return nodes.findById(nodeId);
    }

    public Map<String, StorageNodeEntity> byId() {
        return nodes.findAll().stream().collect(Collectors.toMap(StorageNodeEntity::getNodeId, Function.identity()));
    }

    public List<StorageNodeEntity> healthy() {
        return nodes.findByStatusIn(List.of(NodeStatus.HEALTHY));
    }

    public Map<NodeStatus, Long> countByStatus() {
        Map<NodeStatus, Long> counts = new java.util.EnumMap<>(NodeStatus.class);
        for (NodeStatus s : NodeStatus.values()) {
            counts.put(s, nodes.countByStatus(s));
        }
        return counts;
    }

    public double utilizationOf(String nodeId) {
        return utilization.getOrDefault(nodeId, 0.0);
    }

    /** Heartbeat answered. */
    public synchronized void recordSuccess(String nodeId, NodeHealthReport report) {
        StorageNodeEntity node = nodes.findById(nodeId).orElse(null);
        if (node == null) {
            return;
        }
        NodeStatus before = node.getStatus();
        State next = NodeStateMachine.onSuccess(
                new State(before, node.getConsecutiveFailures(), node.getConsecutiveSuccesses()),
                props.health().recoveryThreshold());
        node.setStatus(next.status());
        node.setConsecutiveFailures(next.failures());
        node.setConsecutiveSuccesses(next.successes());
        node.setTotalCapacity(report.totalCapacity());
        node.setUsedCapacity(report.usedCapacity());
        node.setLastHeartbeat(Instant.now());
        nodes.save(node);
        utilization.put(nodeId, node.utilization());

        if (before != next.status()) {
            log.info("node {} {} -> {}", nodeId, before, next.status());
        }
        if (next.status() == NodeStatus.HEALTHY && (before == NodeStatus.UNHEALTHY || before == NodeStatus.RECOVERING)) {
            events.publish(new VaultEvent.NodeRecovered(nodeId));
        }
    }

    /** Heartbeat missed. */
    public synchronized void recordFailure(String nodeId) {
        StorageNodeEntity node = nodes.findById(nodeId).orElse(null);
        if (node == null) {
            return;
        }
        NodeStatus before = node.getStatus();
        State next = NodeStateMachine.onFailure(
                new State(before, node.getConsecutiveFailures(), node.getConsecutiveSuccesses()),
                props.health().failureThreshold());
        node.setStatus(next.status());
        node.setConsecutiveFailures(next.failures());
        node.setConsecutiveSuccesses(next.successes());
        nodes.save(node);

        if (before != next.status()) {
            log.warn("node {} {} -> {} ({} consecutive failures)", nodeId, before, next.status(), next.failures());
        }
        if (next.status() == NodeStatus.UNHEALTHY && before != NodeStatus.UNHEALTHY) {
            events.publish(new VaultEvent.NodeFailed(nodeId));
        }
    }

    /**
     * A data-path RPC to this node failed. Take it out of placement right away (HEALTHY -> SUSPECTED) but
     * leave the verdict to heartbeats: one failed request must never declare a node dead.
     */
    public synchronized void suspect(String nodeId) {
        nodes.findById(nodeId).ifPresent(node -> {
            if (node.getStatus() == NodeStatus.HEALTHY) {
                node.setStatus(NodeStatus.SUSPECTED);
                nodes.save(node);
                log.warn("node {} HEALTHY -> SUSPECTED after a failed request", nodeId);
            }
        });
    }
}
