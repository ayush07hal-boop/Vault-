package com.vault;

import com.vault.metadata.NodeStatus;
import com.vault.metadata.ObjectEntity;
import com.vault.metadata.ReplicaEntity;
import com.vault.metadata.ReplicaStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Node failure -> NODE_FAILED on Kafka -> consumed -> repair queue -> worker repairs. Broker is an in-process KRaft broker. */
class KafkaEventsTest extends AbstractClusterTest {

    static EmbeddedKafkaBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 3, "vault.node-events", "vault.repair-events");
        broker.afterPropertiesSet();
        backgroundWorkers = true;
    }

    @AfterAll
    static void stopBroker() {
        backgroundWorkers = false;
        broker.destroy();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("vault.kafka.enabled", () -> "true");
        r.add("spring.kafka.bootstrap-servers", () -> broker.getBrokersAsString());
        r.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    private Set<String> liveReplicas(String id) {
        return metadata.replicasOf(id).stream().filter(r -> r.getStatus() == ReplicaStatus.HEALTHY)
                .map(ReplicaEntity::getNodeId).filter(n -> statusOf(n) == NodeStatus.HEALTHY).collect(Collectors.toSet());
    }

    @Test
    void repairIsTriggeredThroughKafka() throws Exception {
        byte[] data = randomBytes(100_000);
        ObjectEntity o = upload(data);
        String victim = liveReplicas(o.getObjectId()).iterator().next();

        server(victim).stop();

        await().atMost(Duration.ofSeconds(45)).until(() -> statusOf(victim) == NodeStatus.UNHEALTHY);
        // repair only happens if NODE_FAILED made the round trip through Kafka (or the 1s scan; both go via the queue)
        await().atMost(Duration.ofSeconds(45)).until(() -> {
            Set<String> live = liveReplicas(o.getObjectId());
            return live.size() == 3 && !live.contains(victim);
        });
        assertEquals(3, liveReplicas(o.getObjectId()).size());
        assertArrayEquals(data, read(o.getObjectId()));
    }
}
