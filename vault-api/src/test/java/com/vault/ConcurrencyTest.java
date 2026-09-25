package com.vault;

import com.vault.error.VaultException;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ReplicaStatus;
import com.vault.storage.ChecksumService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest extends AbstractClusterTest {

    @Autowired ChecksumService checksums;

    private <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }

    @Test
    void hundredSimultaneousReadsAllReturnCorrectBytes() throws Exception {
        byte[] data = randomBytes(200_000);
        String id = upload(data).getObjectId();

        List<Callable<Boolean>> readers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            readers.add(() -> java.util.Arrays.equals(data, read(id)));
        }

        assertTrue(runAll(readers).stream().allMatch(ok -> ok));
    }

    @Test
    void fiftyConcurrentUpdatesFromTheSameVersionYieldExactlyOneWinner() throws Exception {
        String id = upload(randomBytes(1000)).getObjectId();
        AtomicInteger conflicts = new AtomicInteger();

        List<Callable<byte[]>> writers = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            byte[] mine = randomBytes(2000 + i);
            writers.add(() -> {
                try {
                    objectService.update(id, 1, new ByteArrayInputStream(mine), null);
                    return mine;
                } catch (VaultException e) {
                    assertEquals("VERSION_CONFLICT", e.getCode());
                    conflicts.incrementAndGet();
                    return null;
                }
            });
        }
        List<byte[]> outcomes = runAll(writers);

        List<byte[]> winners = outcomes.stream().filter(b -> b != null).toList();
        assertEquals(1, winners.size(), "optimistic concurrency: exactly one writer commits");
        assertEquals(49, conflicts.get());

        ObjectEntity finalState = metadata.find(id).orElseThrow();
        assertEquals(2, finalState.getVersion());
        assertEquals(checksums.sha256(winners.get(0)), finalState.getChecksum());
        assertArrayEquals(winners.get(0), read(id), "stored bytes belong to the winner and are intact");
        assertEquals(3, metadata.replicasOf(id).size());
        assertTrue(metadata.replicasOf(id).stream()
                .allMatch(r -> r.getVersion() == 2 && r.getStatus() == ReplicaStatus.HEALTHY));
        assertEquals(3, nodesHolding(id, 2).size());
        assertTrue(nodesHolding(id, 1).isEmpty());
    }

    @Test
    void sequentialUpdatesChainVersionsWhileReadsRunConcurrently() throws Exception {
        String id = upload(randomBytes(1000)).getObjectId();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        tasks.add(() -> {
            for (int v = 1; v <= 5; v++) {
                objectService.update(id, v, new ByteArrayInputStream(randomBytes(3000)), null);
            }
            return true;
        });
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                for (int n = 0; n < 5; n++) {
                    // every read must return a complete, checksum-valid version (never a torn write)
                    assertEquals(metadata.find(id).orElseThrow().getObjectId(), id);
                    read(id);
                }
                return true;
            });
        }

        assertTrue(runAll(tasks).stream().allMatch(ok -> ok));
        assertEquals(6, metadata.find(id).orElseThrow().getVersion());
    }

    @Test
    void fiftyConcurrentUploadsAllSucceedWithFullReplication() throws Exception {
        List<Callable<String>> uploads = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            byte[] data = randomBytes(5000 + i);
            payloads.add(data);
            uploads.add(() -> upload(data).getObjectId());
        }

        List<String> ids = runAll(uploads);

        assertEquals(50, ids.stream().distinct().count());
        for (int i = 0; i < ids.size(); i++) {
            assertEquals(3, healthyReplicas(ids.get(i)).size());
            assertArrayEquals(payloads.get(i), read(ids.get(i)));
        }
    }
}
