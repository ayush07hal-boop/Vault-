package com.vault.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    public static final String IO_EXECUTOR = "ioExecutor";

    /** Blocking gRPC calls to storage nodes run on cheap virtual threads. */
    @Bean(name = IO_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService ioExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Scheduled loops (heartbeats, repair scan, integrity, rebalance) only run when workers are enabled. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "vault.workers.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
