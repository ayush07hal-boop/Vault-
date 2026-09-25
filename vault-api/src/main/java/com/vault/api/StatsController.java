package com.vault.api;

import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectRepository;
import com.vault.metadata.ObjectStatus;
import com.vault.metadata.ReplicaRepository;
import com.vault.metadata.StorageNodeEntity;
import com.vault.metrics.VaultMetrics;
import com.vault.repair.RepairQueue;
import com.vault.service.NodeService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard numbers in one call. Counters are since controller start; storage numbers come from heartbeats. */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final ObjectRepository objects;
    private final ReplicaRepository replicas;
    private final NodeService nodes;
    private final RepairQueue repairQueue;
    private final VaultMetrics metrics;

    public StatsController(ObjectRepository objects, ReplicaRepository replicas, NodeService nodes,
                           RepairQueue repairQueue, VaultMetrics metrics) {
        this.objects = objects;
        this.replicas = replicas;
        this.nodes = nodes;
        this.repairQueue = repairQueue;
        this.metrics = metrics;
    }

    @GetMapping
    public Map<String, Object> stats() {
        List<StorageNodeEntity> all = nodes.all();
        long total = all.stream().mapToLong(StorageNodeEntity::getTotalCapacity).sum();
        long used = all.stream().mapToLong(StorageNodeEntity::getUsedCapacity).sum();

        Map<String, Long> replicaStatus = new LinkedHashMap<>();
        for (Object[] row : replicas.countGroupedByStatus()) {
            replicaStatus.put(String.valueOf(row[0]), (Long) row[1]);
        }

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("totalCapacityBytes", total);
        storage.put("usedBytes", used);
        storage.put("usedRatio", total == 0 ? 0.0 : (double) used / total);
        storage.put("logicalBytes", objects.sumActiveBytes());

        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("uploads", (long) metrics.uploads.count());
        counters.put("downloads", (long) metrics.downloads.count());
        counters.put("updates", (long) metrics.updates.count());
        counters.put("deletes", (long) metrics.deletes.count());
        counters.put("versionConflicts", (long) metrics.versionConflicts.count());
        counters.put("repairs", (long) metrics.repairs.count());
        counters.put("repairFailures", (long) metrics.repairFailures.count());
        counters.put("corruptionsDetected", (long) metrics.corruptionsDetected.count());
        counters.put("nodeFailures", (long) metrics.nodeFailures.count());
        counters.put("rebalances", (long) metrics.rebalances.count());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeObjects", objects.countByStatus(ObjectStatus.ACTIVE));
        body.put("nodes", nodes.countByStatus());
        body.put("unhealthyNodes", all.stream().filter(n -> n.getStatus() != NodeStatus.HEALTHY).count());
        body.put("storage", storage);
        body.put("replicasByStatus", replicaStatus);
        body.put("objectsNeedingRepair", objects.findObjectIdsNeedingRepair(NodeStatus.REACHABLE, PageRequest.of(0, 1000)).size());
        body.put("repairQueueDepth", repairQueue.size());
        body.put("counters", counters);
        return body;
    }
}
