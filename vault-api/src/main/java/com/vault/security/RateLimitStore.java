package com.vault.security;

/** Fixed-window request counter (Redis INCR+EXPIRE in production, in-memory otherwise). */
public interface RateLimitStore {

    /** @return the count including this request, within the current window */
    long increment(String key, int windowSeconds);
}
