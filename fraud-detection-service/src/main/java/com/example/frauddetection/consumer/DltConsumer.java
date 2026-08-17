package com.example.frauddetection.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes messages that have been routed to the Dead Letter Topic after exhausting
 * all retry attempts in {@link org.springframework.kafka.listener.DefaultErrorHandler}.
 *
 * <p>Spring Kafka's {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}
 * (configured in KafkaConsumerConfig) writes failed records to
 * {@code <original-topic>.DLT} — i.e. {@code transaction.created.DLT} — after
 * 3 retry attempts. This consumer reads from that topic and logs all available
 * diagnostic context so that poison-pill messages are never silently lost.</p>
 *
 * <p>The value is consumed as raw {@code byte[]} rather than a typed object because
 * the most common DLT scenario is a deserialization failure — the payload may be
 * invalid JSON and would fail again if re-deserialized.</p>
 */
@Component
public class DltConsumer {

    private static final Logger log = LoggerFactory.getLogger(DltConsumer.class);

    @KafkaListener(
            topics = "${kafka.dlt.topic:transaction.created.DLT}",
            groupId = "fraud-detection-dlt-group",
            containerFactory = "dltKafkaListenerContainerFactory"
    )
    public void handleDlt(ConsumerRecord<String, byte[]> record) {
        Headers headers = record.headers();

        String exceptionClass   = headerAsString(headers, KafkaHeaders.DLT_EXCEPTION_FQCN);
        String exceptionMessage = headerAsString(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        String originalTopic    = headerAsString(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        String originalPartition = headerAsString(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        String originalOffset   = headerAsString(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET);

        String payload = record.value() != null
                ? new String(record.value(), StandardCharsets.UTF_8)
                : "<null>";

        log.error("[DLT] Poison-pill message received — " +
                        "dltTopic={}, dltPartition={}, dltOffset={}, " +
                        "originalTopic={}, originalPartition={}, originalOffset={}, " +
                        "exceptionClass={}, exceptionMessage={}, " +
                        "payload={}",
                record.topic(), record.partition(), record.offset(),
                originalTopic, originalPartition, originalOffset,
                exceptionClass, exceptionMessage,
                payload);
    }

    private String headerAsString(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : "unknown";
    }
}