package com.vault.replication;

import com.vault.metadata.StorageNodeEntity;
import com.vault.service.NodeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses which nodes receive replicas: healthy nodes with room, least-utilised first, never the same
 * node twice, and spread across failure zones when zones are configured.
 */
@Service
public class PlacementService {

    private final NodeService nodeService;

    public PlacementService(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    /** May return fewer than {@code count} nodes if the cluster cannot satisfy the request. */
    public List<StorageNodeEntity> choose(int count, long objectSizeBytes, Set<String> excludeNodeIds) {
        return select(nodeService.healthy(), count, objectSizeBytes, excludeNodeIds);
    }

    static List<StorageNodeEntity> select(List<StorageNodeEntity> healthyNodes, int count, long objectSizeBytes,
                                          Set<String> excludeNodeIds) {
        List<StorageNodeEntity> candidates = healthyNodes.stream()
                .filter(n -> !excludeNodeIds.contains(n.getNodeId()))
                .filter(n -> n.freeBytes() >= objectSizeBytes)
                .sorted(Comparator.comparingDouble(StorageNodeEntity::utilization)
                        .thenComparing(StorageNodeEntity::getNodeId))
                .toList();

        // Group by zone (nodes without a zone are their own group) and take one per zone per round.
        Map<String, List<StorageNodeEntity>> byZone = new LinkedHashMap<>();
        for (StorageNodeEntity n : candidates) {
            String zone = n.getZone() == null || n.getZone().isBlank() ? "node:" + n.getNodeId() : n.getZone();
            byZone.computeIfAbsent(zone, z -> new ArrayList<>()).add(n);
        }
        List<StorageNodeEntity> chosen = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        while (chosen.size() < count && taken.size() < candidates.size()) {
            List<StorageNodeEntity> round = new ArrayList<>();
            for (List<StorageNodeEntity> zoneNodes : byZone.values()) {
                zoneNodes.stream().filter(n -> !taken.contains(n.getNodeId())).findFirst().ifPresent(round::add);
            }
            round.sort(Comparator.comparingDouble(StorageNodeEntity::utilization)
                    .thenComparing(StorageNodeEntity::getNodeId));
            for (StorageNodeEntity n : round) {
                if (chosen.size() == count) {
                    break;
                }
                chosen.add(n);
                taken.add(n.getNodeId());
            }
        }
        return chosen;
    }
}
