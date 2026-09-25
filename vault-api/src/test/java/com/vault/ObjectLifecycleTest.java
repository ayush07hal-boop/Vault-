package com.vault;

import com.vault.error.VaultException;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ObjectStatus;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaStatus;
import com.vault.service.ObjectService;
import com.vault.storage.ChecksumService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectLifecycleTest extends AbstractClusterTest {

    @Autowired ChecksumService checksums;

    @Test
    void uploadReplicatesToThreeNodesAndDownloadRoundTrips() throws Exception {
        byte[] data = randomBytes(1_000_000);

        ObjectEntity object = upload(data);

        assertEquals(1, object.getVersion());
        assertEquals(3, object.getReplicationFactor());
        assertEquals(checksums.sha256(data), object.getChecksum());
        List<ReplicaEntity> replicas = healthyReplicas(object.getObjectId());
        assertEquals(3, replicas.size());
        assertEquals(3, nodesHolding(object.getObjectId(), 1).size(), "bytes physically present on 3 nodes");
        assertArrayEquals(data, read(object.getObjectId()));
    }

    @Test
    void replicationFactorIsConfigurablePerObject() throws Exception {
        ObjectEntity one = upload(randomBytes(1000), 1);
        ObjectEntity four = upload(randomBytes(1000), 4);

        assertEquals(1, healthyReplicas(one.getObjectId()).size());
        assertEquals(4, healthyReplicas(four.getObjectId()).size());
        assertThrows(VaultException.class, () -> upload(randomBytes(10), 0));
    }

    @Test
    void placementPrefersEmptierNodes() {
        // Fill one node much more than the others via a single-replica object, then check it is avoided.
        ObjectEntity big = upload(randomBytes(4_000_000), 1);
        String fullNode = healthyReplicas(big.getObjectId()).get(0).getNodeId();
        healthMonitor.pollOnce(); // refresh utilisation

        ObjectEntity next = upload(randomBytes(1000), 3);

        assertFalse(healthyReplicas(next.getObjectId()).stream().anyMatch(r -> r.getNodeId().equals(fullNode)),
                "the fullest node should be the one left out of a 3-of-4 placement");
    }

    @Test
    void updateCreatesNewVersionAndRemovesOldFiles() throws Exception {
        ObjectEntity v1 = upload(randomBytes(5000));
        byte[] newer = randomBytes(7000);

        objectService.update(v1.getObjectId(), 1, new ByteArrayInputStream(newer), "renamed.bin");

        ObjectEntity v2 = metadata.find(v1.getObjectId()).orElseThrow();
        assertEquals(2, v2.getVersion());
        assertEquals("renamed.bin", v2.getFileName());
        assertEquals(checksums.sha256(newer), v2.getChecksum());
        assertArrayEquals(newer, read(v1.getObjectId()));
        assertEquals(3, nodesHolding(v1.getObjectId(), 2).size());
        assertTrue(nodesHolding(v1.getObjectId(), 1).isEmpty(), "old version files are cleaned up");
        assertTrue(metadata.replicasOf(v1.getObjectId()).stream()
                .allMatch(r -> r.getVersion() == 2 && r.getStatus() == ReplicaStatus.HEALTHY));
    }

    @Test
    void updateWithStaleVersionIsRejectedWithConflict() throws Exception {
        ObjectEntity object = upload(randomBytes(100));
        byte[] winner = randomBytes(100);
        objectService.update(object.getObjectId(), 1, new ByteArrayInputStream(winner), null);

        VaultException e = assertThrows(VaultException.class, () ->
                objectService.update(object.getObjectId(), 1, new ByteArrayInputStream(randomBytes(100)), null));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals("VERSION_CONFLICT", e.getCode());
        assertArrayEquals(winner, read(object.getObjectId()), "loser must not overwrite the winner");
    }

    @Test
    void deleteTombstonesObjectAndRemovesEveryReplica() {
        ObjectEntity object = upload(randomBytes(2000));
        assertEquals(3, nodesHolding(object.getObjectId(), 1).size());

        objectService.delete(object.getObjectId());

        assertTrue(nodesHolding(object.getObjectId(), 1).isEmpty());
        assertEquals(ObjectStatus.DELETED, metadata.find(object.getObjectId()).orElseThrow().getStatus(),
                "tombstone is kept so stale replicas can never resurrect the object");
        assertTrue(metadata.replicasOf(object.getObjectId()).isEmpty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(VaultException.class, () -> objectService.download(object.getObjectId())).getStatus());
        objectService.delete(object.getObjectId()); // idempotent
    }

    @Test
    void deleteWithANodeDownCompletesOnceItReturns() {
        ObjectEntity object = upload(randomBytes(2000));
        String victim = healthyReplicas(object.getObjectId()).get(0).getNodeId();
        crash(victim);

        objectService.delete(object.getObjectId());

        assertEquals(ObjectStatus.DELETING, metadata.find(object.getObjectId()).orElseThrow().getStatus());
        assertEquals(1, metadata.replicasOf(object.getObjectId()).size(), "only the unreachable copy is left");

        restartNode(indexOf(victim));
        for (int i = 0; i < 3; i++) {
            healthMonitor.pollOnce();
        }
        // A freshly restarted node may need a moment to accept connections; the sweep simply retries, as in production.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).pollInterval(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                .until(() -> {
                    garbageCollector.collect(100);
                    return metadata.replicasOf(object.getObjectId()).isEmpty();
                });
        assertEquals(ObjectStatus.DELETED, metadata.find(object.getObjectId()).orElseThrow().getStatus());
        assertTrue(nodesHolding(object.getObjectId(), 1).isEmpty());
    }

    @Test
    void idempotencyKeyMakesUploadRetriesSafe() {
        byte[] data = randomBytes(500);
        ObjectService.CreateResult first = objectService.create(new ByteArrayInputStream(data), "a.bin", null, "key-1");
        ObjectService.CreateResult retry = objectService.create(new ByteArrayInputStream(data), "a.bin", null, "key-1");

        assertFalse(first.replayed());
        assertTrue(retry.replayed());
        assertEquals(first.object().objectId(), retry.object().objectId());
    }

    @Autowired com.vault.repair.ReplicaGarbageCollector garbageCollector;
}
