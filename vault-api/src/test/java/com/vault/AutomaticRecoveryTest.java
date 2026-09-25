package com.vault;

import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end "kill a node" test with the real background workers running (heartbeat monitor, repair scanner,
 * repair queue workers): nothing here calls a repair method by hand.
 */
class AutomaticRecoveryTest extends AbstractClusterTest {

    @BeforeAll
    static void enableWorkers() {
        backgroundWorkers = true;
    }

    @AfterAll
    static void disableWorkers() {
        backgroundWorkers = false;
    }

    private Set<String> liveHealthyReplicaNodes(String objectId) {
        return metadata.replicasOf(objectId).stream()
                .filter(r -> r.getStatus() == ReplicaStatus.HEALTHY)
                .map(ReplicaEntity::getNodeId)
                .filter(n -> statusOf(n) == NodeStatus.HEALTHY)
                .collect(Collectors.toSet());
    }

    @Test
    void killedNodeIsDetectedAndItsDataReReplicatedAutomatically() throws Exception {
        byte[] data = randomBytes(500_000);
        ObjectEntity object = upload(data);
        String id = object.getObjectId();
        Set<String> before = liveHealthyReplicaNodes(id);
        assertEquals(3, before.size());
        String victim = before.iterator().next();

        long killedAt = System.nanoTime();
        server(victim).stop();

        await().atMost(Duration.ofSeconds(30)).pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> statusOf(victim) == NodeStatus.UNHEALTHY);
        long detectedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - killedAt);

        await().atMost(Duration.ofSeconds(30)).pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> liveHealthyReplicaNodes(id).size() == 3);
        long repairedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - killedAt);

        Set<String> after = liveHealthyReplicaNodes(id);
        assertTrue(!after.contains(victim));
        assertEquals(3, nodesHolding(id, 1).stream().filter(n -> !n.equals(victim)).count());
        assertArrayEquals(data, read(id));
        System.out.printf("RECOVERY: failure detected after %d ms, replication factor restored after %d ms%n",
                detectedMs, repairedMs);
        assertTrue(repairedMs < 30_000);

        // and the whole thing is visible through the cluster health summary
        List<ReplicaEntity> rows = metadata.replicasOf(id);
        assertEquals(4, rows.size(), "dead node's row is retained until it returns or is decommissioned");
    }
}
