package com.vault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** Typed view of the {@code vault.*} configuration tree. */
@ConfigurationProperties("vault")
public record VaultProperties(
        @DefaultValue Replication replication,
        @DefaultValue Quorum quorum,
        @DefaultValue Health health,
        @DefaultValue Repair repair,
        @DefaultValue Integrity integrity,
        @DefaultValue Rebalance rebalance,
        @DefaultValue Storage storage,
        @DefaultValue Grpc grpc,
        @DefaultValue Workers workers,
        @DefaultValue Kafka kafka,
        @DefaultValue Redis redis,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Security security,
        @DefaultValue Cors cors,
        @DefaultValue Admin admin,
        @DefaultValue Auth auth,
        @DefaultValue UserQuota userQuota,
        @DefaultValue Trash trash,
        List<NodeSpec> nodes) {

    public VaultProperties {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public record Replication(@DefaultValue("3") int factor) {
    }

    /**
     * R + W must exceed N for reads and writes to overlap. {@code enforceRead} makes reads fail
     * unless at least R current-version replicas are reachable.
     */
    public record Quorum(@DefaultValue("2") int read,
                         @DefaultValue("2") int write,
                         @DefaultValue("false") boolean enforceRead) {
    }

    public record Health(@DefaultValue("5") int heartbeatIntervalSeconds,
                         @DefaultValue("3") int failureThreshold,
                         @DefaultValue("2") int recoveryThreshold) {
    }

    public record Repair(@DefaultValue("10") int intervalSeconds,
                         @DefaultValue("5") int maxConcurrentTasks,
                         @DefaultValue("1000") int scanLimit,
                         /* Pause repairs when more than this fraction of nodes is unreachable (likely a partition). */
                         @DefaultValue("0.5") double partitionGuardFraction) {
    }

    public record Integrity(@DefaultValue("true") boolean enabled,
                            @DefaultValue("3600") int intervalSeconds,
                            @DefaultValue("200") int batchSize) {
    }

    public record Rebalance(@DefaultValue("true") boolean enabled,
                            @DefaultValue("300") int intervalSeconds,
                            @DefaultValue("80") int thresholdPercent,
                            @DefaultValue("5") int minImprovementPercent,
                            @DefaultValue("10") int maxMovesPerRun) {
    }

    public record Storage(@DefaultValue("SHA-256") String checksumAlgorithm) {
    }

    public record Grpc(@DefaultValue("3000") int rpcTimeoutMillis,
                       @DefaultValue("600000") int transferTimeoutMillis,
                       @DefaultValue("4") int maxAttempts,
                       @DefaultValue("100") int baseBackoffMillis) {
    }

    /** Set {@code vault.workers.enabled=false} to turn off every background loop (tests drive them by hand). */
    public record Workers(@DefaultValue("true") boolean enabled) {
    }

    /** Publish events to Kafka (consumed by whichever controller instance owns the partition). Off = in-process bus. */
    public record Kafka(@DefaultValue("false") boolean enabled,
                        @DefaultValue("vault.node-events") String nodeEventsTopic,
                        @DefaultValue("vault.repair-events") String repairEventsTopic,
                        @DefaultValue("vault-controller") String groupId) {
    }

    /** Redis-backed distributed object locks (needed to run more than one controller). Off = in-process locks. */
    public record Redis(@DefaultValue("false") boolean enabled,
                        @DefaultValue("600") int lockTtlSeconds) {
    }

    public record RateLimit(@DefaultValue("false") boolean enabled,
                            @DefaultValue("600") int requestsPerMinute) {
    }

    /** Blank apiKey = authentication disabled (prototype default). */
    public record Security(@DefaultValue("") String apiKey) {
    }

    /** Browser origins allowed to call the API (comma-separated in YAML/env). */
    public record Cors(@DefaultValue({"http://localhost:3000", "http://localhost:5173"}) List<String> allowedOrigins) {
    }

    /**
     * Admin access = signed in with a Google account whose email is on {@code emails} AND knows {@code password}.
     * Blank password: a random one is generated and logged at startup. Blank secret: random per start.
     */
    public record Admin(List<String> emails,
                        @DefaultValue("") String password,
                        @DefaultValue("") String tokenSecret,
                        @DefaultValue("480") int adminTokenTtlMinutes,
                        @DefaultValue("720") int userTokenTtlMinutes) {
        public Admin {
            emails = emails == null ? List.of() : emails.stream().map(e -> e.toLowerCase().trim()).filter(e -> !e.isEmpty()).toList();
        }
    }

    /** Storage each account may use (4 GiB by default). Uploads beyond it are refused with 507. */
    public record UserQuota(@DefaultValue("4294967296") long bytes) {
    }

    /** Trashed files are permanently deleted after this many days. */
    public record Trash(@DefaultValue("30") int retentionDays, @DefaultValue("3600") int purgeIntervalSeconds) {
    }

    /** Google Sign-In. {@code devLogin} lets anyone sign in with any email (local demos only!). */
    public record Auth(@DefaultValue("") String googleClientId, @DefaultValue("false") boolean devLogin) {
    }

    public record NodeSpec(String id, String host, int port, String zone) {
    }
}
