package com.vault.health;

import com.vault.config.AsyncConfig;
import com.vault.metadata.StorageNodeEntity;
import com.vault.metrics.VaultMetrics;
import com.vault.service.NodeService;
import com.vault.storage.NodeRpcException;
import com.vault.storage.StorageNodeClient;
import com.vault.storage.StorageNodeClient.NodeHealthReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Heartbeats every node on a fixed interval and feeds the results into {@link NodeService}. */
@Component
public class HealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitor.class);

    private final NodeService nodeService;
    private final StorageNodeClient client;
    private final VaultMetrics metrics;
    private final ExecutorService io;

    public HealthMonitor(NodeService nodeService, StorageNodeClient client, VaultMetrics metrics,
                         @Qualifier(AsyncConfig.IO_EXECUTOR) ExecutorService io) {
        this.nodeService = nodeService;
        this.client = client;
        this.metrics = metrics;
        this.io = io;
    }

    @Scheduled(fixedDelayString = "${vault.health.heartbeat-interval-seconds:5}", initialDelay = 0,
            timeUnit = TimeUnit.SECONDS)
    void scheduledHeartbeat() {
        try {
            pollOnce();
        } catch (Exception e) {
            log.error("heartbeat round failed", e);
        }
    }

    /** One heartbeat round; nodes are probed in parallel so one slow node cannot delay detection of others. */
    public void pollOnce() {
        List<StorageNodeEntity> nodes = nodeService.all();
        List<CompletableFuture<Void>> probes = nodes.stream()
                .map(n -> CompletableFuture.runAsync(() -> probe(n), io))
                .toList();
        probes.forEach(CompletableFuture::join);
    }

    private void probe(StorageNodeEntity node) {
        metrics.trackNode(node.getNodeId());
        try {
            NodeHealthReport report = client.health(node);
            if (report.healthy()) {
                nodeService.recordSuccess(node.getNodeId(), report);
            } else {
                nodeService.recordFailure(node.getNodeId());
            }
        } catch (NodeRpcException e) {
            log.debug("heartbeat to {} failed: {}", node.getNodeId(), e.getMessage());
            nodeService.recordFailure(node.getNodeId());
        }
    }
}
