package com.example.frauddetection.consumer;

import com.example.frauddetection.model.TransactionCreatedEvent;
import com.example.frauddetection.service.TransactionPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionConsumerTest {

    @Mock
    private TransactionPersistenceService persistenceService;

    @Mock
    private Acknowledgment ack;

    @InjectMocks
    private TransactionConsumer consumer;

    // --- Helper ---

    private TransactionCreatedEvent event(String txId) {
        return new TransactionCreatedEvent(
                txId,
                "ACC-001",
                1_500.0,
                "RETAIL",
                "MERCHANT-001",
                "POS",
                Instant.parse("2024-01-15T08:00:00Z")
        );
    }

    // --- Happy path ---

    @Test
    void listen_delegatesToPersistenceService_andAcknowledges() {
        TransactionCreatedEvent event = event("TXN-001");

        consumer.listen(event, ack);

        verify(persistenceService).processAndPersist(event);
        verify(ack).acknowledge();
    }

    @Test
    void listen_acknowledgesAfterSuccessfulProcessing() {
        // Acknowledgment must only arrive once processing completes — never before
        TransactionCreatedEvent event = event("TXN-002");

        consumer.listen(event, ack);

        var inOrder = inOrder(persistenceService, ack);
        inOrder.verify(persistenceService).processAndPersist(event);
        inOrder.verify(ack).acknowledge();
    }

    // --- Null-event guard ---

    @Test
    void listen_nullEvent_acknowledgesImmediately_withoutCallingPersistenceService() {
        consumer.listen(null, ack);

        verify(persistenceService, never()).processAndPersist(any());
        verify(ack).acknowledge();
    }

    // --- Error path ---

    @Test
    void listen_whenPersistenceServiceThrows_rethrowsException() {
        TransactionCreatedEvent event = event("TXN-003");
        RuntimeException cause = new RuntimeException("DB unavailable");
        doThrow(cause).when(persistenceService).processAndPersist(event);

        assertThatThrownBy(() -> consumer.listen(event, ack))
                .isSameAs(cause);
    }

    @Test
    void listen_whenPersistenceServiceThrows_doesNotAcknowledge() {
        // Offset must not advance so the broker retries (or routes to DLT via DefaultErrorHandler)
        TransactionCreatedEvent event = event("TXN-004");
        doThrow(new RuntimeException("DB unavailable")).when(persistenceService).processAndPersist(event);

        try {
            consumer.listen(event, ack);
        } catch (RuntimeException ignored) { }

        verify(ack, never()).acknowledge();
    }
}