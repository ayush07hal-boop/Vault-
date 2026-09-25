package com.vault.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryRateLimitStore implements RateLimitStore {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public long increment(String key, int windowSeconds) {
        long window = System.currentTimeMillis() / 1000 / windowSeconds;
        if (counters.size() > 10_000) {
            counters.clear(); // crude bound; windows are short-lived anyway
        }
        return counters.computeIfAbsent(key + ":" + window, k -> new AtomicLong()).incrementAndGet();
    }
}
