package com.example.frauddetection.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatNoException;

class DltConsumerTest {

    private static final String DLT_TOPIC = "transaction.created.DLT";

    private final DltConsumer dltConsumer = new DltConsumer();

    // --- Helper ---

    private ConsumerRecord<String, byte[]> buildRecord(byte[] payload, Map<String, String> headerMap) {
        RecordHeaders headers = new RecordHeaders();
        headerMap.forEach((k, v) -> headers.add(k, v.getBytes(StandardCharsets.UTF_8)));
        return new ConsumerRecord<>(
                DLT_TOPIC, 0, 42L,
                0L, TimestampType.CREATE_TIME,
                -1, -1, null, payload, headers, Optional.empty()
        );
    }

    // --- Happy path ---

    @Test
    void handleDlt_validPayloadWithAllHeaders_logsAndDoesNotThrow() {
        var record = buildRecord(
                "{\"transactionId\":\"TXN-001\",\"accountId\":\"ACC-001\",\"amount\":15000.0}".getBytes(StandardCharsets.UTF_8),
                Map.of(
                        KafkaHeaders.DLT_EXCEPTION_FQCN, "java.lang.RuntimeException",
                        KafkaHeaders.DLT_EXCEPTION_MESSAGE, "DB unavailable",
                        KafkaHeaders.DLT_ORIGINAL_TOPIC, "transaction.created",
                        KafkaHeaders.DLT_ORIGINAL_PARTITION, "0",
                        KafkaHeaders.DLT_ORIGINAL_OFFSET, "41"
                )
        );

        assertThatNoException().isThrownBy(() -> dltConsumer.handleDlt(record));
    }

    // --- Null / missing data ---

    @Test
    void handleDlt_nullPayload_doesNotThrow() {
        var record = buildRecord(null, Map.of(
                KafkaHeaders.DLT_EXCEPTION_FQCN, "java.lang.NullPointerException",
                KafkaHeaders.DLT_ORIGINAL_TOPIC, "transaction.created"
        ));

        assertThatNoException().isThrownBy(() -> dltConsumer.handleDlt(record));
    }

    @Test
    void handleDlt_noHeaders_doesNotThrow() {
        var record = buildRecord(
                "{\"transactionId\":\"TXN-002\"}".getBytes(StandardCharsets.UTF_8),
                Map.of()
        );

        assertThatNoException().isThrownBy(() -> dltConsumer.handleDlt(record));
    }

    // --- Malformed payload ---

    @Test
    void handleDlt_malformedJsonPayload_doesNotThrow() {
        var record = buildRecord(
                "this-is-not-json".getBytes(StandardCharsets.UTF_8),
                Map.of(
                        KafkaHeaders.DLT_EXCEPTION_FQCN, "com.fasterxml.jackson.core.JsonParseException",
                        KafkaHeaders.DLT_EXCEPTION_MESSAGE, "Unexpected character at position 0",
                        KafkaHeaders.DLT_ORIGINAL_TOPIC, "transaction.created",
                        KafkaHeaders.DLT_ORIGINAL_OFFSET, "99"
                )
        );

        assertThatNoException().isThrownBy(() -> dltConsumer.handleDlt(record));
    }

    @Test
    void handleDlt_binaryGarbagePayload_doesNotThrow() {
        byte[] corruptBytes = {(byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0xAB};
        var record = buildRecord(corruptBytes, Map.of(
                KafkaHeaders.DLT_EXCEPTION_FQCN, "org.springframework.kafka.support.serializer.DeserializationException",
                KafkaHeaders.DLT_ORIGINAL_TOPIC, "transaction.created"
        ));

        assertThatNoException().isThrownBy(() -> dltConsumer.handleDlt(record));
    }
}