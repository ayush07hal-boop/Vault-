package com.vault.replication;

import com.vault.metadata.StorageNodeEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementServiceTest {

    private static StorageNodeEntity node(String id, String zone, long used, long total) {
        StorageNodeEntity n = new StorageNodeEntity(id, "localhost", 1, zone);
        n.setTotalCapacity(total);
        n.setUsedCapacity(used);
        return n;
    }

    @Test
    void picksLeastUtilisedNodes() {
        List<StorageNodeEntity> nodes = List.of(
                node("n1", null, 80, 100), node("n2", null, 30, 100), node("n3", null, 40, 100),
                node("n4", null, 25, 100), node("n5", null, 60, 100));

        List<String> chosen = PlacementService.select(nodes, 3, 1, Set.of()).stream()
                .map(StorageNodeEntity::getNodeId).toList();

        assertEquals(List.of("n4", "n2", "n3"), chosen);
    }

    @Test
    void neverPicksExcludedOrFullNodes() {
        List<StorageNodeEntity> nodes = List.of(
                node("n1", null, 0, 100), node("n2", null, 99, 100), node("n3", null, 10, 100));

        List<String> chosen = PlacementService.select(nodes, 3, 50, Set.of("n1")).stream()
                .map(StorageNodeEntity::getNodeId).toList();

        assertEquals(List.of("n3"), chosen, "n1 excluded, n2 lacks 50 free bytes");
    }

    @Test
    void neverReturnsTheSameNodeTwice() {
        List<StorageNodeEntity> nodes = List.of(node("n1", "a", 0, 100), node("n2", "a", 0, 100));

        List<StorageNodeEntity> chosen = PlacementService.select(nodes, 5, 1, Set.of());

        assertEquals(2, chosen.size());
    }

    @Test
    void spreadsReplicasAcrossFailureZonesBeforeReusingAZone() {
        List<StorageNodeEntity> nodes = List.of(
                node("a1", "zone-a", 0, 100), node("a2", "zone-a", 1, 100),
                node("b1", "zone-b", 50, 100), node("c1", "zone-c", 60, 100));

        List<String> zones = PlacementService.select(nodes, 3, 1, Set.of()).stream()
                .map(StorageNodeEntity::getZone).toList();

        assertEquals(3, Set.copyOf(zones).size(), "one replica per zone even though zone-a nodes are emptier");
        assertTrue(zones.containsAll(List.of("zone-a", "zone-b", "zone-c")));
    }
}
