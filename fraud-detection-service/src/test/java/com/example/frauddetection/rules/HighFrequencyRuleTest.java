package com.example.frauddetection.rules;

import com.example.frauddetection.config.FraudRulesConfig;
import com.example.frauddetection.entity.Transaction;
import com.example.frauddetection.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HighFrequencyRuleTest {

    @Mock
    private TransactionRepository transactionRepository;

    private FraudRulesConfig config;
    private HighFrequencyRule rule;

    @BeforeEach
    void setUp() {
        // Default config: maxCount=5, windowMinutes=10
        config = new FraudRulesConfig();
        rule = new HighFrequencyRule(config, transactionRepository);
    }

    // --- Helper ---

    private Transaction txForAccount(String accountId) {
        Transaction tx = new Transaction();
        tx.setId("TXN-001");
        tx.setAccountId(accountId);
        tx.setAmount(500.0);
        return tx;
    }

    // --- Tests ---

    @Test
    void fires_whenCountReachesMaxCount() {
        when(transactionRepository.countRecentTransactions(eq("ACC-001"), any(Instant.class)))
                .thenReturn(5L); // exactly at the threshold

        List<FraudFlagResult> results = rule.evaluate(txForAccount("ACC-001"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRuleName()).isEqualTo("HIGH_FREQUENCY");
        assertThat(results.get(0).getSeverity()).isEqualTo("HIGH");
    }

    @Test
    void fires_whenCountExceedsMaxCount() {
        when(transactionRepository.countRecentTransactions(eq("ACC-001"), any(Instant.class)))
                .thenReturn(10L);

        assertThat(rule.evaluate(txForAccount("ACC-001"))).hasSize(1);
    }

    @Test
    void doesNotFire_whenCountBelowMaxCount() {
        when(transactionRepository.countRecentTransactions(eq("ACC-001"), any(Instant.class)))
                .thenReturn(4L);

        assertThat(rule.evaluate(txForAccount("ACC-001"))).isEmpty();
    }

    @Test
    void doesNotFire_whenRuleIsDisabled() {
        config.getHighFrequency().setEnabled(false);

        assertThat(rule.evaluate(txForAccount("ACC-001"))).isEmpty();

        // Repository should never be queried when rule is disabled
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void queuesWindowStart_approximately_nowMinusWindowMinutes() {
        when(transactionRepository.countRecentTransactions(any(), any())).thenReturn(0L);

        Instant before = Instant.now().minusSeconds(config.getHighFrequency().getWindowMinutes() * 60L + 2);
        rule.evaluate(txForAccount("ACC-001"));
        Instant after  = Instant.now().minusSeconds(config.getHighFrequency().getWindowMinutes() * 60L - 2);

        ArgumentCaptor<Instant> windowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(transactionRepository).countRecentTransactions(eq("ACC-001"), windowCaptor.capture());

        Instant captured = windowCaptor.getValue();
        assertThat(captured).isAfter(before).isBefore(after);
    }

    @Test
    void detailMessage_containsCountAccountAndWindow() {
        when(transactionRepository.countRecentTransactions(eq("ACC-002"), any(Instant.class)))
                .thenReturn(7L);

        List<FraudFlagResult> results = rule.evaluate(txForAccount("ACC-002"));

        assertThat(results.get(0).getDetail())
                .contains("7")
                .contains("ACC-002")
                .contains("10"); // windowMinutes
    }

    @Test
    void fires_withCustomThresholdAndWindow() {
        config.getHighFrequency().setMaxCount(3);
        config.getHighFrequency().setWindowMinutes(5);

        when(transactionRepository.countRecentTransactions(any(), any())).thenReturn(3L);

        assertThat(rule.evaluate(txForAccount("ACC-001"))).hasSize(1);
    }
}
