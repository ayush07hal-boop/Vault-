package com.vault.replication;

/** Minimal distributed-lock primitive (Redis SET NX PX in production, in-memory in tests). */
public interface LockStore {

    boolean tryAcquire(String key, String token, long ttlMillis);

    /** Releases only if {@code token} still owns the lock. */
    void release(String key, String token);
}
