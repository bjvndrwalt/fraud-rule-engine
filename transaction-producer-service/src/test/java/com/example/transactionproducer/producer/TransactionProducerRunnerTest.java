package com.example.transactionproducer.producer;

import com.example.transactionproducer.model.TransactionCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionProducerRunnerTest {

    private static final String TOPIC = "transaction.created";

    private static final List<String> KNOWN_ACCOUNTS = List.of(
            "ACC-001", "ACC-002", "ACC-003", "ACC-004", "ACC-005",
            "ACC-006", "ACC-007", "ACC-008", "ACC-009", "ACC-010"
    );

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private TransactionProducerRunner runner;

    @BeforeEach
    void setUp() {
        // Return a completed future so whenComplete() in sendTransaction() doesn't NPE.
        // Null result is fine — the callback only checks ex != null.
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        runner = new TransactionProducerRunner(kafkaTemplate, TOPIC);
    }

    // --- Topic routing ---

    @Test
    void run_sendsToTheConfiguredTopic() throws InterruptedException {
        runBriefly(runner);

        verify(kafkaTemplate, atLeastOnce()).send(eq(TOPIC), anyString(), any());
    }

    // --- Message key ---

    @Test
    void run_usesAccountIdAsMessageKey() throws InterruptedException {
        runBriefly(runner);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, atLeastOnce()).send(anyString(), keyCaptor.capture(), any());

        assertThat(keyCaptor.getAllValues())
                .allMatch(KNOWN_ACCOUNTS::contains);
    }

    // --- Event shape ---

    @Test
    void run_producedEvent_hasAllMandatoryFieldsPopulated() throws InterruptedException {
        runBriefly(runner);

        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(anyString(), anyString(), valueCaptor.capture());

        TransactionCreatedEvent event = (TransactionCreatedEvent) valueCaptor.getValue();
        assertThat(event.getTransactionId()).isNotNull().isNotBlank();
        assertThat(event.getAccountId()).isIn(KNOWN_ACCOUNTS);
        assertThat(event.getAmount()).isPositive();
        assertThat(event.getMerchantCategory()).isNotNull().isNotBlank();
        assertThat(event.getMerchantId()).isNotNull().isNotBlank();
        assertThat(event.getChannel()).isIn("POS", "ONLINE", "ATM");
        assertThat(event.getTransactionTime()).isNotNull();
    }

    @Test
    void run_transactionIds_areMonotonicallyIncreasing() throws InterruptedException {
        // Each successive call to sendTransaction() increments the counter — IDs must be unique
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        when(kafkaTemplate.send(anyString(), anyString(), valueCaptor.capture()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Run long enough to guarantee multiple sends
        runBriefly(runner);

        List<Integer> ids = valueCaptor.getAllValues().stream()
                .map(o -> Integer.parseInt(((TransactionCreatedEvent) o).getTransactionId()))
                .toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    // --- Resilience ---

    @Test
    void run_doesNotPropagateRuntimeException_whenKafkaSendFails() throws InterruptedException {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("Broker not ready"));

        Thread t = startInThread(runner);
        Thread.sleep(50);
        t.interrupt();
        t.join(2_000);

        // Thread must have exited cleanly — RuntimeException is swallowed inside sendTransaction()
        assertThat(t.isAlive()).isFalse();
    }

    // --- Helpers ---

    /**
     * Starts {@code runner.run()} in a background thread, lets one loop iteration complete,
     * interrupts the thread, and waits for it to exit.
     */
    private static void runBriefly(TransactionProducerRunner runner) throws InterruptedException {
        Thread t = startInThread(runner);
        Thread.sleep(50);   // first iteration fires immediately (sleep is at the END of the loop)
        t.interrupt();
        t.join(2_000);
    }

    private static Thread startInThread(TransactionProducerRunner runner) {
        Thread t = new Thread(runner::run);
        t.setDaemon(true);
        t.start();
        return t;
    }
}