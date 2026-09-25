package com.vault.api;

import com.vault.config.VaultProperties;
import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectRepository;
import com.vault.metadata.ObjectStatus;
import com.vault.repair.RepairQueue;
import com.vault.service.NodeService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cluster-level health: is data currently as safe as it should be? (JVM liveness is /actuator/health.) */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final NodeService nodes;
    private final ObjectRepository objects;
    private final RepairQueue repairQueue;
    private final VaultProperties props;

    public HealthController(NodeService nodes, ObjectRepository objects, RepairQueue repairQueue, VaultProperties props) {
        this.nodes = nodes;
        this.objects = objects;
        this.repairQueue = repairQueue;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<NodeStatus, Long> byStatus = nodes.countByStatus();
        int needingRepair = objects.findObjectIdsNeedingRepair(NodeStatus.REACHABLE,
                PageRequest.of(0, props.repair().scanLimit())).size();
        long reachable = byStatus.get(NodeStatus.HEALTHY) + byStatus.get(NodeStatus.SUSPECTED)
                + byStatus.get(NodeStatus.RECOVERING);
        String status = reachable == 0 ? "DOWN" : (needingRepair > 0 || byStatus.get(NodeStatus.UNHEALTHY) > 0
                ? "DEGRADED" : "UP");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("nodes", byStatus);
        body.put("activeObjects", objects.countByStatus(ObjectStatus.ACTIVE));
        body.put("objectsNeedingRepair", needingRepair);
        body.put("repairQueueDepth", repairQueue.size());
        return body;
    }
}
