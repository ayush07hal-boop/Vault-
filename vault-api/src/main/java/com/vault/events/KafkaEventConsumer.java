package com.vault.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds events read from Kafka into the local handlers (repair worker etc.). All controller instances share one
 * consumer group, so each event is handled by exactly one instance.
 */
@Component
@ConditionalOnProperty(name = "vault.kafka.enabled", havingValue = "true")
public class KafkaEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);

    private final ApplicationEventPublisher local;
    private final ObjectMapper json;

    public KafkaEventConsumer(ApplicationEventPublisher local, ObjectMapper json) {
        this.local = local;
        this.json = json;
    }

    @KafkaListener(topics = {"${vault.kafka.node-events-topic:vault.node-events}",
            "${vault.kafka.repair-events-topic:vault.repair-events}"},
            groupId = "${vault.kafka.group-id:vault-controller}")
    void onMessage(String payload) {
        try {
            local.publishEvent(json.readValue(payload, EventMessage.class).toEvent());
        } catch (Exception e) {
            log.error("dropping unreadable event {}: {}", payload, e.getMessage());
        }
    }
}
