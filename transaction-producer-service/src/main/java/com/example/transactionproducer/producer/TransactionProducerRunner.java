package com.example.transactionproducer.producer;

import com.example.transactionproducer.model.TransactionCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TransactionProducerRunner implements CommandLineRunner {

    private static final List<String> ACCOUNT_IDS = List.of(
            "ACC-001", "ACC-002", "ACC-003", "ACC-004", "ACC-005",
            "ACC-006", "ACC-007", "ACC-008", "ACC-009", "ACC-010"
    );

    private static final List<String> ROUND_AMOUNTS = List.of(
            "1000.0", "2000.0", "5000.0", "10000.0", "15000.0"
    );

    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String transactionTopic;
    private final Random random = new Random();
    private final AtomicInteger counter = new AtomicInteger(1);

    public TransactionProducerRunner(KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${TRANSACTION_TOPIC:transaction.created}") String transactionTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTopic = transactionTopic;
    }

    @Override
    public void run(String... args) {
        System.out.println("[TransactionProducer] Started. Publishing to topic: " + transactionTopic);

        while (!Thread.currentThread().isInterrupted()) {

            // 2% chance of a burst — fires 6 rapid events for one account to trigger HIGH_FREQUENCY
            if (random.nextInt(100) < 2) {
                String burstAccount = ACCOUNT_IDS.get(random.nextInt(ACCOUNT_IDS.size()));
                System.out.println("[TransactionProducer] Burst triggered for " + burstAccount);
                for (int i = 0; i < 6; i++) {
                    sendTransaction(burstAccount);
                }
            } else {
                String accountId = ACCOUNT_IDS.get(random.nextInt(ACCOUNT_IDS.size()));
                sendTransaction(accountId);
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendTransaction(String accountId) {
        TransactionCreatedEvent event = new TransactionCreatedEvent(
                String.valueOf(counter.getAndIncrement()),
                accountId,
                randomAmount(),
                randomMerchantCategory(),
                UUID.randomUUID().toString(),
                randomChannel(),
                randomTransactionTime()
        );

        System.out.println("[TransactionProducer] Publishing transaction:"
                + " id="       + event.getTransactionId()
                + " | account=" + accountId
                + " | amount=R" + event.getAmount()
                + " | category=" + event.getMerchantCategory()
                + " | channel=" + event.getChannel()
                + " | time="    + event.getTransactionTime());

        try {
            kafkaTemplate.send(transactionTopic, accountId, event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            System.err.println("[TransactionProducer] Failed to send "
                                    + event.getTransactionId() + ": " + ex.getMessage());
                        }
                    });
        } catch (RuntimeException ex) {
            System.err.println("[TransactionProducer] Producer not ready for "
                    + event.getTransactionId() + ": " + ex.getMessage());
        }
    }

    private double randomAmount() {
        int roll = random.nextInt(100);
        if (roll < 80) {
            // Normal: R20 – R5000
            return Math.round((20.0 + random.nextDouble() * 4980.0) * 100.0) / 100.0;
        } else if (roll < 90) {
            // High amount: R10001 – R100000
            return Math.round((10001.0 + random.nextDouble() * 89999.0) * 100.0) / 100.0;
        } else if (roll < 95) {
            // Round amount: triggers ROUND_AMOUNT rule
            double[] rounds = {1000.0, 2000.0, 5000.0, 10000.0, 15000.0};
            return rounds[random.nextInt(rounds.length)];
        } else {
            // Very round + high: triggers both ROUND_AMOUNT and HIGH_AMOUNT
            double[] bigRounds = {20000.0, 50000.0};
            return bigRounds[random.nextInt(bigRounds.length)];
        }
    }

    private String randomMerchantCategory() {
        int roll = random.nextInt(100);
        if (roll < 40)      return "RETAIL";
        else if (roll < 60) return "GROCERY";
        else if (roll < 75) return "FUEL";
        else if (roll < 85) return "RESTAURANT";
        else if (roll < 90) return "TRAVEL";
        else if (roll < 93) return "GAMBLING";
        else if (roll < 97) return "CRYPTO";
        else                return "FOREX";
    }

    private String randomChannel() {
        int roll = random.nextInt(100);
        if (roll < 60)      return "POS";
        else if (roll < 90) return "ONLINE";
        else                return "ATM";
    }

    private Instant randomTransactionTime() {
        // 10% chance of a synthetic unusual hour (02:xx SAST) to trigger UNUSUAL_HOUR rule
        if (random.nextInt(100) < 10) {
            return LocalDateTime.now(SAST)
                    .withHour(2)
                    .withMinute(random.nextInt(60))
                    .withSecond(random.nextInt(60))
                    .atZone(SAST)
                    .toInstant();
        }
        return Instant.now();
    }
}
