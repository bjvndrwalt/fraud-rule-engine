package com.example.frauddetection.consumer;

import com.example.frauddetection.model.TransactionCreatedEvent;
import com.example.frauddetection.service.TransactionPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TransactionConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionConsumer.class);

    private final TransactionPersistenceService persistenceService;

    public TransactionConsumer(TransactionPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @KafkaListener(topics = "${TRANSACTION_TOPIC:transaction.created}", groupId = "fraud-detection-group")
    public void listen(TransactionCreatedEvent event, Acknowledgment ack) {
        if (event == null) {
            ack.acknowledge();
            return;
        }
        try {
            persistenceService.processAndPersist(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing transaction {}: {}", event.getTransactionId(), e.getMessage(), e);
            throw e;
        }
    }
}
