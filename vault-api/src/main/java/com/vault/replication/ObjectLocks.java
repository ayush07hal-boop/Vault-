package com.vault.replication;

import com.vault.config.VaultProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-object mutual exclusion for repair, rebalancing, garbage collection and updates.
 * <p>Always takes a striped in-process lock. When a {@link LockStore} is configured (Redis) the outermost hold
 * additionally takes a cluster-wide lock, so several controller instances never work on one object at once.
 * Re-entrant: nested acquisitions in one thread only touch the local lock. Distributed locks expire after
 * {@code vault.redis.lock-ttl-seconds} so a crashed controller cannot block an object forever.
 */
@Component
public class ObjectLocks {

    private final ReentrantLock[] stripes = new ReentrantLock[256];
    private final LockStore store;
    private final long ttlMillis;
    private final String owner = UUID.randomUUID().toString();

    @Autowired
    public ObjectLocks(ObjectProvider<LockStore> store, VaultProperties props) {
        this(store.getIfAvailable(), props.redis().lockTtlSeconds() * 1000L);
    }

    public ObjectLocks(LockStore store, long ttlMillis) {
        this.store = store;
        this.ttlMillis = ttlMillis;
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    public Lock lockFor(String objectId) {
        ReentrantLock local = stripes[Math.floorMod(objectId.hashCode(), stripes.length)];
        return store == null ? local : new Distributed(objectId, local);
    }

    private final class Distributed implements Lock {
        private final String key;
        private final ReentrantLock local;
        private final String token = owner + ":" + Thread.currentThread().threadId();

        Distributed(String key, ReentrantLock local) {
            this.key = key;
            this.local = local;
        }

        @Override
        public void lock() {
            local.lock();
            if (local.getHoldCount() > 1) {
                return; // nested: already hold the cluster lock
            }
            try {
                while (!store.tryAcquire(key, token, ttlMillis)) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                }
            } catch (RuntimeException e) {
                local.unlock();
                throw e;
            }
        }

        @Override
        public void unlock() {
            try {
                if (local.getHoldCount() == 1) {
                    store.release(key, token);
                }
            } finally {
                local.unlock();
            }
        }

        @Override
        public void lockInterruptibly() {
            lock();
        }

        @Override
        public boolean tryLock() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }
}
