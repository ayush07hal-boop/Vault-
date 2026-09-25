package com.vault;

import com.vault.events.EventMessage;
import com.vault.events.VaultEvent;
import com.vault.replication.InMemoryLockStore;
import com.vault.replication.ObjectLocks;
import com.vault.security.InMemoryRateLimitStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfraTest {

    @Test
    void everyEventSurvivesTheKafkaWireFormat() {
        List<VaultEvent> events = List.of(new VaultEvent.NodeFailed("n1"), new VaultEvent.NodeRecovered("n1"),
                new VaultEvent.ReplicaMissing("o", "n"), new VaultEvent.ReplicaCorrupted("o", "n"),
                new VaultEvent.ReplicaOutdated("o", "n"), new VaultEvent.ObjectUnderReplicated("o"));
        for (VaultEvent e : events) {
            assertEquals(e, EventMessage.of(e).toEvent());
        }
    }

    @Test
    void twoControllersSharingALockStoreNeverHoldTheSameObjectTogether() throws Exception {
        InMemoryLockStore shared = new InMemoryLockStore(); // stands in for Redis
        ObjectLocks controllerA = new ObjectLocks(shared, 60_000);
        ObjectLocks controllerB = new ObjectLocks(shared, 60_000);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                ObjectLocks who = i % 2 == 0 ? controllerA : controllerB;
                done.add(pool.submit(() -> {
                    start.await();
                    Lock lock = who.lockFor("obj-1");
                    lock.lock();
                    try {
                        maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                        Thread.sleep(5);
                        inside.decrementAndGet();
                    } finally {
                        lock.unlock();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        }
        assertEquals(1, maxInside.get(), "mutual exclusion across controllers");
    }

    @Test
    void distributedLockIsReentrantAndReleasedOnlyByTheOutermostUnlock() {
        InMemoryLockStore shared = new InMemoryLockStore();
        ObjectLocks a = new ObjectLocks(shared, 60_000);
        Lock outer = a.lockFor("o");
        outer.lock();
        Lock inner = a.lockFor("o");
        inner.lock();
        inner.unlock();
        assertTrue(!shared.tryAcquire("o", "someone-else", 1000), "still held by the outer lock");
        outer.unlock();
        assertTrue(shared.tryAcquire("o", "someone-else", 1000), "released after outermost unlock");
    }

    @Test
    void rateLimitStoreCountsWithinAWindow() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        assertEquals(1, store.increment("c", 3600));
        assertEquals(2, store.increment("c", 3600));
        assertEquals(1, store.increment("other", 3600));
    }
}
