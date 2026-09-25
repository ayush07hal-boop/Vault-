package com.vault;

import com.vault.error.VaultException;
import com.vault.integrity.IntegrityVerifier;
import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaStatus;
import com.vault.repair.PartitionGuard;
import com.vault.repair.RepairQueue;
import com.vault.repair.RepairScanner;
import com.vault.repair.RepairService;
import com.vault.repair.RepairService.Outcome;
import com.vault.storage.ChecksumService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The failure scenarios from the design: node loss, multiple losses, corruption, partition, stale replicas. */
class FailureScenariosTest extends AbstractClusterTest {

    @Autowired RepairService repairService;
    @Autowired RepairScanner repairScanner;
    @Autowired RepairQueue repairQueue;
    @Autowired IntegrityVerifier integrityVerifier;
    @Autowired PartitionGuard partitionGuard;
    @Autowired ChecksumService checksums;

    private List<String> drainQueue() throws InterruptedException {
        List<String> drained = new java.util.ArrayList<>();
        while (repairQueue.size() > 0) {
            drained.add(repairQueue.take());
        }
        return drained;
    }

    private Set<String> replicaNodes(String objectId) {
        return healthyReplicas(objectId).stream().map(ReplicaEntity::getNodeId).collect(Collectors.toSet());
    }

    // ---- Scenario 1: one node fails ---------------------------------------------------------------

    @Test
    void singleNodeFailureIsDetectedThenRepairedOntoTheSpareNode() throws Exception {
        byte[] data = randomBytes(300_000);
        ObjectEntity object = upload(data);
        String id = object.getObjectId();
        Set<String> before = replicaNodes(id);
        String victim = before.iterator().next();
        String spare = List.of("node-1", "node-2", "node-3", "node-4").stream()
                .filter(n -> !before.contains(n)).findFirst().orElseThrow();

        // one missed heartbeat: suspected only, no repair action
        server(victim).stop();
        healthMonitor.pollOnce();
        assertEquals(NodeStatus.SUSPECTED, statusOf(victim));
        assertEquals(Outcome.HEALTHY, repairService.repairObject(id), "a suspected node must not trigger repair");

        // second miss crosses the threshold
        healthMonitor.pollOnce();
        assertEquals(NodeStatus.UNHEALTHY, statusOf(victim));
        // The NodeFailed event has already queued the node's objects; the periodic scan is the safety net.
        repairScanner.scan();
        assertTrue(drainQueue().contains(id), "object is queued for repair");

        assertEquals(Outcome.REPAIRED, repairService.repairObject(id));

        Set<String> after = replicaNodes(id);
        assertTrue(after.contains(spare), "new replica lands on the spare node");
        assertEquals(3, after.stream().filter(n -> !n.equals(victim)).count(), "3 healthy replicas off the dead node");
        // the copied bytes really are correct
        assertEquals(checksums.sha256(Files.readAllBytes(server(spare).getStore().pathOf(id, 1))), object.getChecksum());
        assertArrayEquals(data, read(id));
    }

    @Test
    void downloadKeepsWorkingWhileANodeIsDownBeforeRepair() throws Exception {
        byte[] data = randomBytes(10_000);
        ObjectEntity object = upload(data);
        List<String> holders = List.copyOf(replicaNodes(object.getObjectId()));

        // Two of three holders crash; no heartbeat has run, so the controller still believes they are HEALTHY.
        server(holders.get(0)).stop();
        server(holders.get(1)).stop();

        for (int i = 0; i < 10; i++) {
            assertArrayEquals(data, read(object.getObjectId()), "read falls through to the surviving replica");
        }
    }

    // ---- Scenario 2: multiple nodes fail ---------------------------------------------------------

    @Test
    void multipleNodeFailuresRepairAsFarAsPossibleAndNeverPretendDurability() throws Exception {
        byte[] data = randomBytes(50_000);
        ObjectEntity object = upload(data);
        String id = object.getObjectId();
        List<String> holders = List.copyOf(replicaNodes(id));

        crash(holders.get(0));
        crash(holders.get(1));
        // 2 of 4 nodes down is exactly at (not over) the partition-guard limit, so repair proceeds
        assertFalse(partitionGuard.isTripped());

        assertEquals(Outcome.PARTIAL, repairService.repairObject(id),
                "only two healthy nodes remain, so 3 replicas cannot be restored");
        assertEquals(2, healthyReplicas(id).stream()
                .filter(r -> statusOf(r.getNodeId()) == NodeStatus.HEALTHY).count());
        assertArrayEquals(data, read(id), "still readable with reduced redundancy");

        // system health must admit the degradation
        assertTrue(objectService.metadata(id).healthyReplicas() < 3);

        // lose the last remaining copy holder too
        String last = healthyReplicas(id).stream().map(ReplicaEntity::getNodeId)
                .filter(n -> statusOf(n) == NodeStatus.HEALTHY).findFirst().orElseThrow();
        String other = healthyReplicas(id).stream().map(ReplicaEntity::getNodeId)
                .filter(n -> statusOf(n) == NodeStatus.HEALTHY && !n.equals(last)).findFirst().orElseThrow();
        crash(last);
        crash(other);

        VaultException e = assertThrows(VaultException.class, () -> objectService.download(id));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertEquals("INSUFFICIENT_REPLICAS", e.getCode());
    }

    @Test
    void writesSucceedWithQuorumButFailBelowIt() {
        ObjectEntity probe = upload(randomBytes(10));
        crash("node-3");
        crash("node-4"); // 2 healthy nodes left = write quorum of 2

        ObjectEntity degraded = upload(randomBytes(1000));
        assertEquals(2, healthyReplicas(degraded.getObjectId()).size(), "written with W=2 despite RF=3");

        crash("node-2"); // only 1 healthy node: quorum impossible
        VaultException e = assertThrows(VaultException.class, () -> upload(randomBytes(1000)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertTrue(probe.getObjectId().startsWith("obj-"));
    }

    // ---- Scenario 3: corrupted replica ------------------------------------------------------------

    @Test
    void backgroundIntegrityCheckFindsCorruptionAndRepairsFromAGoodReplica() throws Exception {
        byte[] data = randomBytes(200_000);
        ObjectEntity object = upload(data);
        String id = object.getObjectId();
        String corruptNode = replicaNodes(id).iterator().next();
        Path file = server(corruptNode).getStore().pathOf(id, 1);
        byte[] rotted = Files.readAllBytes(file);
        rotted[1234] ^= 0x55; // silent bit rot, same length
        Files.write(file, rotted);

        IntegrityVerifier.Report report = integrityVerifier.verifyBatch();

        assertEquals(1, report.corrupted());
        assertEquals(ReplicaStatus.CORRUPTED, metadata.replicasOf(id).stream()
                .filter(r -> r.getNodeId().equals(corruptNode)).findFirst().orElseThrow().getStatus());

        assertEquals(Outcome.REPAIRED, repairService.repairObject(id));

        assertEquals(3, healthyReplicas(id).size());
        assertArrayEquals(data, Files.readAllBytes(file), "corrupted file was overwritten with verified bytes");
    }

    @Test
    void readTimeVerificationSkipsCorruptReplicaAndFlagsIt() throws Exception {
        byte[] data = randomBytes(80_000);
        ObjectEntity object = upload(data);
        String id = object.getObjectId();
        // corrupt every replica except one, so whichever the reader tries first is likely bad
        List<String> nodes = List.copyOf(replicaNodes(id));
        for (String n : nodes.subList(0, 2)) {
            Files.write(server(n).getStore().pathOf(id, 1), randomBytes(80_000));
        }

        for (int i = 0; i < 5; i++) {
            assertArrayEquals(data, read(id), "client must never receive corrupt bytes");
        }

        integrityVerifier.verifyBatch(); // catches whichever bad copies the readers happened to skip
        long corrupted = metadata.replicasOf(id).stream().filter(r -> r.getStatus() == ReplicaStatus.CORRUPTED).count();
        assertEquals(2, corrupted);
        assertEquals(Outcome.REPAIRED, repairService.repairObject(id));
        assertEquals(3, healthyReplicas(id).size());
    }

    @Test
    void deletedFileOnAHealthyNodeIsDetectedAndRestored() throws Exception {
        byte[] data = randomBytes(5000);
        ObjectEntity object = upload(data);
        String id = object.getObjectId();
        String node = replicaNodes(id).iterator().next();
        Files.delete(server(node).getStore().pathOf(id, 1));

        assertEquals(1, integrityVerifier.verifyBatch().missing());
        assertEquals(Outcome.REPAIRED, repairService.repairObject(id));

        assertTrue(server(node).getStore().find(id, 1).isPresent());
        assertArrayEquals(data, Files.readAllBytes(server(node).getStore().pathOf(id, 1)));
    }

    // ---- Scenario 4: network partition ------------------------------------------------------------

    @Test
    void partitionGuardPausesRepairWhenMostOfTheClusterIsUnreachable() throws Exception {
        ObjectEntity object = upload(randomBytes(20_000));
        String id = object.getObjectId();
        Set<String> holders = replicaNodes(id);
        int rowsBefore = metadata.replicasOf(id).size();

        // "our side" loses sight of 3 of 4 nodes
        List<String> all = List.of("node-1", "node-2", "node-3", "node-4");
        for (String n : all.subList(0, 3)) {
            server(n).stop();
        }
        for (int i = 0; i < 2; i++) {
            healthMonitor.pollOnce();
        }

        assertTrue(partitionGuard.isTripped());
        assertEquals(0, repairScanner.scan(), "no repair traffic during a suspected partition");
        assertEquals(rowsBefore, metadata.replicasOf(id).size(), "nothing destructive happened to metadata");

        // partition heals
        restoreCluster();
        assertFalse(partitionGuard.isTripped());
        assertEquals(holders, replicaNodes(id));
        assertEquals(Outcome.HEALTHY, repairService.repairObject(id));
        assertArrayEquals(Files.readAllBytes(server(holders.iterator().next()).getStore().pathOf(id, 1)), read(id));
    }

    @Test
    void returningNodeTriggersTrimBackToReplicationFactor() throws Exception {
        byte[] data = randomBytes(30_000);
        ObjectEntity object = upload(data);
        String id = object.getObjectId();
        String victim = replicaNodes(id).iterator().next();

        crash(victim);
        assertEquals(Outcome.REPAIRED, repairService.repairObject(id)); // re-created elsewhere
        assertEquals(4, metadata.replicasOf(id).size(), "dead node's row is kept, not destroyed");

        restartNode(indexOf(victim));
        for (int i = 0; i < 3; i++) {
            healthMonitor.pollOnce(); // UNHEALTHY -> RECOVERING -> HEALTHY
        }
        assertEquals(NodeStatus.HEALTHY, statusOf(victim));

        assertEquals(Outcome.REPAIRED, repairService.repairObject(id), "surplus copy is trimmed");
        assertEquals(3, metadata.replicasOf(id).size());
        assertEquals(3, nodesHolding(id, 1).size(), "exactly RF copies remain on disk");
        assertArrayEquals(data, read(id));
    }

    // ---- Scenario 6: inconsistent replicas --------------------------------------------------------

    @Test
    void replicaThatMissedAnUpdateIsMarkedOutdatedAndBroughtUpToDate() throws Exception {
        ObjectEntity object = upload(randomBytes(4000));
        String id = object.getObjectId();
        String laggard = replicaNodes(id).iterator().next();
        server(laggard).stop(); // crashes between heartbeats

        byte[] v2 = randomBytes(6000);
        objectService.update(id, 1, new ByteArrayInputStream(v2), null);

        ReplicaEntity stale = metadata.replicasOf(id).stream()
                .filter(r -> r.getNodeId().equals(laggard)).findFirst().orElseThrow();
        assertEquals(NodeStatus.SUSPECTED, statusOf(laggard), "failed write marks the node suspect right away");
        assertEquals(ReplicaStatus.OUTDATED, stale.getStatus());
        assertEquals(1, stale.getVersion());
        assertEquals(2, healthyReplicas(id).size());

        restartNode(indexOf(laggard));
        for (int i = 0; i < 3; i++) {
            healthMonitor.pollOnce();
        }
        assertEquals(Outcome.REPAIRED, repairService.repairObject(id));

        assertEquals(3, healthyReplicas(id).size());
        assertTrue(metadata.replicasOf(id).stream().allMatch(r -> r.getVersion() == 2));
        assertTrue(server(laggard).getStore().find(id, 1).isEmpty(), "old version removed from the laggard");
        assertArrayEquals(v2, Files.readAllBytes(server(laggard).getStore().pathOf(id, 2)));
    }

    @Test
    void repairOfDeletedOrUnknownObjectIsANoOp() {
        ObjectEntity object = upload(randomBytes(100));
        objectService.delete(object.getObjectId());

        assertEquals(Outcome.SKIPPED, repairService.repairObject(object.getObjectId()));
        assertEquals(Outcome.SKIPPED, repairService.repairObject("obj-does-not-exist"));
        assertEquals(new HashSet<String>(), new HashSet<>(nodesHolding(object.getObjectId(), 1)));
    }
}
