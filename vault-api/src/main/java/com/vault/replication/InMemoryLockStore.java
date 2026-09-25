package com.vault.replication;

import java.util.concurrent.ConcurrentHashMap;

/** Shared-map lock store; two ObjectLocks sharing one instance behave like two controllers sharing Redis. */
public class InMemoryLockStore implements LockStore {

    private record Entry(String token, long expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> locks = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, String token, long ttlMillis) {
        long now = System.currentTimeMillis();
        boolean[] won = {false};
        locks.compute(key, (k, cur) -> {
            if (cur == null || cur.expiresAt() <= now) {
                won[0] = true;
                return new Entry(token, now + ttlMillis);
            }
            return cur;
        });
        return won[0];
    }

    @Override
    public void release(String key, String token) {
        locks.computeIfPresent(key, (k, cur) -> cur.token().equals(token) ? null : cur);
    }
}
