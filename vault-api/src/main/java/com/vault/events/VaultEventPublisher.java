package com.vault.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vault.config.VaultProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Single entry point for domain events. With vault.kafka.enabled=true events go to Kafka
 * (node events and repair events on separate topics) and come back through KafkaEventConsumer;
 * otherwise they are delivered in-process. Producers and consumers of events are identical either way.
 */
@Component
public class VaultEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(VaultEventPublisher.class);

    private final ApplicationEventPublisher local;
    private final ObjectProvider<KafkaTemplate<String, String>> kafka;
    private final ObjectMapper json;
    private final VaultProperties props;

    public VaultEventPublisher(ApplicationEventPublisher local, ObjectProvider<KafkaTemplate<String, String>> kafka,
                               ObjectMapper json, VaultProperties props) {
        this.local = local;
        this.kafka = kafka;
        this.json = json;
        this.props = props;
    }

    public void publish(VaultEvent event) {
        KafkaTemplate<String, String> template = props.kafka().enabled() ? kafka.getIfAvailable() : null;
        if (template == null) {
            local.publishEvent(event);
            return;
        }
        EventMessage msg = EventMessage.of(event);
        String topic = msg.isNodeEvent() ? props.kafka().nodeEventsTopic() : props.kafka().repairEventsTopic();
        String key = msg.objectId() != null ? msg.objectId() : msg.nodeId();
        try {
            template.send(topic, key, json.writeValueAsString(msg)).whenComplete((r, err) -> {
                if (err != null) {
                    // Kafka unreachable: never lose a repair trigger, fall back to the in-process bus.
                    log.warn("kafka publish failed ({}), delivering {} locally", err.getMessage(), msg.type());
                    local.publishEvent(event);
                }
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
