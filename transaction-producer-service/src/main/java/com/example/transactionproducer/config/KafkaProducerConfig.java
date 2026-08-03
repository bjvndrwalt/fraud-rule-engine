package com.example.transactionproducer.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // acks=all: leader waits for all ISRs before acknowledging.
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        // Idempotence: PID + sequence-number dedup at the broker.
        // Guarantees exactly-once delivery within one producer session.
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // Defer retry control entirely to delivery.timeout.ms.
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

        // Total delivery timeout including all retry attempts and backoff.
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

        // Backoff between retries — prevents hammering a recovering broker.
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);

        // Must be <= 5 when idempotence is enabled.
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // Wait up to 5ms to fill a batch before flushing.
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);

        // Flush at linger.ms OR batch.size, whichever comes first.
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32_768);

        // lz4: best default — near-zero decompression overhead, good ratio on JSON.
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // Total record accumulator memory.
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33_554_432L);

        // How long send() blocks for buffer space before throwing.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(
            ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic transactionTopic(
            @Value("${TRANSACTION_TOPIC:transaction.created}") String topicName) {
        return TopicBuilder.name(topicName)
                .partitions(3)
                .replicas(3)
                .build();
    }
}
