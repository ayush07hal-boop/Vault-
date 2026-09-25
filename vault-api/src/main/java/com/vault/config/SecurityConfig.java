package com.vault.config;

import com.vault.security.InMemoryRateLimitStore;
import com.vault.security.RateLimitStore;
import com.vault.security.RedisRateLimitStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class SecurityConfig {

    /** Redis-backed when vault.redis.enabled=true (limits are then shared by all controllers). */
    @Bean
    RateLimitStore rateLimitStore(VaultProperties props, ObjectProvider<StringRedisTemplate> redis) {
        return props.redis().enabled() && redis.getIfAvailable() != null
                ? new RedisRateLimitStore(redis.getObject())
                : new InMemoryRateLimitStore();
    }
}
