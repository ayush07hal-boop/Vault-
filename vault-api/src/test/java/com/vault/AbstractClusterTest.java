package com.vault;

import com.vault.health.HealthMonitor;
import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaStatus;
import com.vault.node.StorageNodeServer;
import com.vault.service.NodeService;
import com.vault.service.ObjectService;
import com.vault.metadata.MetadataService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Boots the real Spring application against real storage nodes (gRPC servers on temp directories, in-process)
 * and an in-memory PostgreSQL-compatible database. Background loops are disabled so tests drive heartbeats,
 * repair, integrity and rebalancing deterministically. Each test class gets a fresh cluster and context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@org.springframework.context.annotation.Import(AbstractClusterTest.FakeGoogle.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractClusterTest {

    protected static final int NODE_COUNT = 4;
    protected static final long NODE_CAPACITY = 10L * 1024 * 1024;

    protected static Path root;
    protected static List<StorageNodeServer> cluster;
    private static List<Integer> ports;
    /** Subclasses that want the real scheduled loops flip this in their own @BeforeAll (and reset it after). */
    protected static boolean backgroundWorkers = false;

    @Autowired protected ObjectService objectService;
    @Autowired protected MetadataService metadata;
    @Autowired protected NodeService nodeService;
    @Autowired protected HealthMonitor healthMonitor;
    @Autowired protected com.vault.security.Tokens tokens;
    @Autowired protected com.vault.service.UserService userService;

    protected static final String ADMIN_EMAIL = "boss@example.com";
    protected static final String ALICE = "alice@example.com";
    protected static final String BOB = "bob@example.com";

    /** "Authorization" header value for a signed-in account (created on demand). */
    protected String userAuth(String email) {
        var u = userService.upsert("test:" + email, email, email, null);
        return "Bearer " + tokens.issue(com.vault.security.Tokens.Kind.USER, u.getUserId(), email).token();
    }

    protected String userIdOf(String email) {
        return userService.upsert("test:" + email, email, email, null).getUserId();
    }

    /** "X-Admin-Token" header value for the allowlisted admin account. */
    protected String adminToken() {
        return adminTokenFor(ADMIN_EMAIL);
    }

    protected String adminTokenFor(String email) {
        var u = userService.upsert("test:" + email, email, email, null);
        return tokens.issue(com.vault.security.Tokens.Kind.ADMIN, u.getUserId(), email).token();
    }

    /** Stands in for Google: credential "good:sub:email" is accepted, anything else is rejected. */
    @org.springframework.boot.test.context.TestConfiguration
    static class FakeGoogle {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.vault.security.GoogleIdentityVerifier fakeGoogle() {
            return new com.vault.security.GoogleIdentityVerifier() {
                @Override
                public java.util.Optional<Identity> verify(String token) {
                    String[] p = token == null ? new String[0] : token.split(":");
                    return p.length == 3 && p[0].equals("good")
                            ? java.util.Optional.of(new Identity(p[1], p[2], "Test " + p[2], null)) : java.util.Optional.empty();
                }

                @Override
                public boolean isConfigured() {
                    return true;
                }
            };
        }
    }

    @BeforeAll
    static void startCluster() throws Exception {
        root = Files.createTempDirectory("vault-test-");
        cluster = new ArrayList<>();
        ports = new ArrayList<>();
        for (int i = 1; i <= NODE_COUNT; i++) {
            StorageNodeServer node = new StorageNodeServer("node-" + i, 0, root.resolve("node-" + i), NODE_CAPACITY).start();
            cluster.add(node);
            ports.add(node.getPort());
        }
    }

    @AfterAll
    static void stopCluster() throws IOException {
        cluster.forEach(n -> {
            if (n.isRunning()) {
                n.stop();
            }
        });
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:vault-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.username", () -> "sa");
        r.add("spring.datasource.password", () -> "");
        r.add("vault.admin.password", () -> "test-pass");
        r.add("vault.admin.emails", () -> ADMIN_EMAIL);
        r.add("vault.auth.dev-login", () -> "true");
        r.add("vault.workers.enabled", () -> String.valueOf(backgroundWorkers));
        r.add("vault.health.heartbeat-interval-seconds", () -> "1");
        r.add("vault.repair.interval-seconds", () -> "1");
        r.add("vault.health.failure-threshold", () -> "2");
        r.add("vault.grpc.max-attempts", () -> "2");
        r.add("vault.grpc.base-backoff-millis", () -> "20");
        r.add("vault.grpc.rpc-timeout-millis", () -> "1000");
        for (int i = 0; i < NODE_COUNT; i++) {
            int index = i;
            r.add("vault.nodes[" + i + "].id", () -> "node-" + (index + 1));
            r.add("vault.nodes[" + i + "].host", () -> "localhost");
            r.add("vault.nodes[" + i + "].port", () -> String.valueOf(ports.get(index)));
            r.add("vault.nodes[" + i + "].zone", () -> "zone-" + (index + 1));
        }
    }

    @BeforeEach
    void healthyCluster() {
        restoreCluster();
    }

    @AfterEach
    void afterEach() {
        restoreCluster();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Restarts any stopped node (same port, same disk) and runs heartbeats until every node is HEALTHY. */
    protected void restoreCluster() {
        for (int i = 0; i < NODE_COUNT; i++) {
            if (!cluster.get(i).isRunning()) {
                restartNode(i);
            }
        }
        for (int round = 0; round < 3; round++) {
            healthMonitor.pollOnce();
        }
    }

    protected void restartNode(int index) {
        try {
            StorageNodeServer old = cluster.get(index);
            cluster.set(index, new StorageNodeServer(old.getNodeId(), ports.get(index), old.getDataDir(), NODE_CAPACITY).start());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static int indexOf(String nodeId) {
        return Integer.parseInt(nodeId.substring("node-".length())) - 1;
    }

    protected StorageNodeServer server(String nodeId) {
        return cluster.get(indexOf(nodeId));
    }

    /** Crashes the node and runs enough heartbeats for the monitor to declare it UNHEALTHY. */
    protected void crash(String nodeId) {
        server(nodeId).stop();
        for (int i = 0; i < 2; i++) {
            healthMonitor.pollOnce();
        }
    }

    protected NodeStatus statusOf(String nodeId) {
        return nodeService.find(nodeId).orElseThrow().getStatus();
    }

    protected static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random().nextBytes(data);
        return data;
    }

    protected ObjectEntity upload(byte[] data) {
        return upload(data, null);
    }

    protected ObjectEntity upload(byte[] data, Integer replicationFactor) {
        String id = objectService.create(new ByteArrayInputStream(data), "test.bin", replicationFactor, null)
                .object().objectId();
        return metadata.find(id).orElseThrow();
    }

    protected byte[] read(String objectId) throws IOException {
        ObjectService.Download d = objectService.download(objectId);
        try {
            return Files.readAllBytes(d.file());
        } finally {
            Files.deleteIfExists(d.file());
        }
    }

    protected List<ReplicaEntity> healthyReplicas(String objectId) {
        return metadata.replicasOf(objectId).stream().filter(r -> r.getStatus() == ReplicaStatus.HEALTHY).toList();
    }

    protected List<String> nodesHolding(String objectId, long version) {
        return cluster.stream()
                .filter(n -> n.getStore().find(objectId, version).isPresent())
                .map(StorageNodeServer::getNodeId)
                .toList();
    }
}
