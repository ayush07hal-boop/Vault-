package com.vault;

import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaStatus;
import com.vault.rebalance.Rebalancer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {"vault.rebalance.threshold-percent=50", "vault.rebalance.max-moves-per-run=20"})
class RebalancerTest extends AbstractClusterTest {

    @Autowired Rebalancer rebalancer;

    @Test
    void overloadedNodeShedsDataToEmptierNodesWithoutLosingAnything() throws Exception {
        // Force skew: with nodes 2-4 down, every single-replica object can only land on node-1.
        crash("node-2");
        crash("node-3");
        crash("node-4");
        Map<String, byte[]> stored = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            byte[] data = randomBytes(1_000_000);
            ObjectEntity o = upload(data, 1);
            assertEquals("node-1", metadata.replicasOf(o.getObjectId()).get(0).getNodeId());
            stored.put(o.getObjectId(), data);
        }
        restoreCluster();
        long usedBefore = server("node-1").getStore().usedBytes();
        assertTrue(usedBefore * 100 / NODE_CAPACITY > 50, "node-1 is over the 50% threshold");

        int moved = rebalancer.runOnce();
        assertTrue(moved > 0);
        healthMonitor.pollOnce();

        long usedAfter = server("node-1").getStore().usedBytes();
        assertTrue(usedAfter < usedBefore);
        assertTrue(usedAfter * 100 / NODE_CAPACITY <= 50, "node-1 brought back under the threshold");

        List<String> nodesUsed = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : stored.entrySet()) {
            List<ReplicaEntity> rows = metadata.replicasOf(e.getKey());
            assertEquals(1, rows.size(), "still exactly one replica: moves neither duplicate nor drop data");
            assertEquals(ReplicaStatus.HEALTHY, rows.get(0).getStatus());
            assertEquals(1, nodesHolding(e.getKey(), 1).size(), "old copy removed only after the new one was committed");
            assertEquals(rows.get(0).getNodeId(), nodesHolding(e.getKey(), 1).get(0));
            assertArrayEquals(e.getValue(), read(e.getKey()));
            nodesUsed.add(rows.get(0).getNodeId());
        }
        assertTrue(nodesUsed.stream().anyMatch(n -> !n.equals("node-1")));
    }

    @Test
    void balancedClusterIsLeftAlone() {
        upload(randomBytes(10_000));
        assertEquals(0, rebalancer.runOnce());
    }
}
