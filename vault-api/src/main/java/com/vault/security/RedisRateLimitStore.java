package com.vault.security;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

public class RedisRateLimitStore implements RateLimitStore {

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, int windowSeconds) {
        String k = "vault:rl:" + key + ":" + System.currentTimeMillis() / 1000 / windowSeconds;
        Long n = redis.opsForValue().increment(k);
        if (n != null && n == 1) {
            redis.expire(k, Duration.ofSeconds(windowSeconds * 2L));
        }
        return n == null ? 0 : n;
    }
}
