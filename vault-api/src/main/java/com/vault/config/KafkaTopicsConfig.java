package com.vault.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "vault.kafka.enabled", havingValue = "true")
public class KafkaTopicsConfig {

    @Bean
    NewTopic nodeEventsTopic(VaultProperties p) {
        return TopicBuilder.name(p.kafka().nodeEventsTopic()).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic repairEventsTopic(VaultProperties p) {
        return TopicBuilder.name(p.kafka().repairEventsTopic()).partitions(3).replicas(1).build();
    }
}
