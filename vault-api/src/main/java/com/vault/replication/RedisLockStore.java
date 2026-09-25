package com.vault.replication;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(name = "vault.redis.enabled", havingValue = "true")
public class RedisLockStore implements LockStore {

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisLockStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, String token, long ttlMillis) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("vault:lock:" + key, token, Duration.ofMillis(ttlMillis)));
    }

    @Override
    public void release(String key, String token) {
        redis.execute(RELEASE, List.of("vault:lock:" + key), token);
    }
}
