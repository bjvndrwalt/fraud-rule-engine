package com.example.frauddetection.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration for the Dead Letter Topic (DLT) consumer.
 *
 * <p>A separate {@link ConsumerFactory} and
 * {@link ConcurrentKafkaListenerContainerFactory} are provided for the DLT
 * consumer because DLT messages must be treated differently from normal
 * transaction events:</p>
 * <ul>
 *   <li>The value is deserialized as raw {@code byte[]} — the original payload
 *       may be malformed JSON, which is precisely why it ended up in the DLT.</li>
 *   <li>Auto-commit is enabled — the DLT consumer only logs; there is no
 *       complex failure path that would require manual offset management.</li>
 *   <li>Concurrency is set to 1 — DLT volume is low and single-threaded
 *       processing keeps the log output ordered.</li>
 * </ul>
 *
 * <p>The factory is referenced by name ({@code dltKafkaListenerContainerFactory})
 * in {@link com.example.frauddetection.consumer.DltConsumer}.</p>
 */
@Configuration
public class KafkaDltConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, byte[]> dltConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "fraud-detection-dlt-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> dltKafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> dltConsumerFactory
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(dltConsumerFactory);
        factory.setConcurrency(1);
        return factory;
    }
}