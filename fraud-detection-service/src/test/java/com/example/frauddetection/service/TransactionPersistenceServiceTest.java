package com.example.frauddetection.service;

import com.example.frauddetection.entity.FraudFlag;
import com.example.frauddetection.entity.Transaction;
import com.example.frauddetection.model.TransactionCreatedEvent;
import com.example.frauddetection.repository.FraudFlagRepository;
import com.example.frauddetection.repository.TransactionRepository;
import com.example.frauddetection.rules.FraudFlagResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionPersistenceServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private FraudFlagRepository fraudFlagRepository;

    @Mock
    private FraudClassifier fraudClassifierService;

    @InjectMocks
    private TransactionPersistenceService service;

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

    // --- Idempotency ---

    @Test
    void skips_duplicateTransaction() {
        when(transactionRepository.existsById("TXN-001")).thenReturn(true);

        service.processAndPersist(event("TXN-001"));

        // Should not attempt to save a duplicate
        verify(transactionRepository, never()).save(any());
        verify(fraudClassifierService, never()).evaluate(any());
    }

    // --- Happy path: no fraud ---

    @Test
    void saves_newTransaction_whenNothingFlagged() {
        when(transactionRepository.existsById("TXN-001")).thenReturn(false);
        when(fraudClassifierService.evaluate(any())).thenReturn(Collections.emptyList());

        service.processAndPersist(event("TXN-001"));

        // Saved once (before rules); no second save needed since nothing was flagged
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(fraudFlagRepository, never()).save(any());
    }

    @Test
    void transaction_isSavedBeforeRulesRun() {
        // The order matters: HIGH_FREQUENCY counts the current tx from the DB,
        // so it must be persisted first.
        when(transactionRepository.existsById("TXN-001")).thenReturn(false);
        when(fraudClassifierService.evaluate(any())).thenReturn(Collections.emptyList());

        service.processAndPersist(event("TXN-001"));

        // Verify save happened before evaluate
        var inOrder = inOrder(transactionRepository, fraudClassifierService);
        inOrder.verify(transactionRepository).save(any(Transaction.class));
        inOrder.verify(fraudClassifierService).evaluate(any(Transaction.class));
    }

    @Test
    void transaction_mappedCorrectly_fromEvent() {
        when(transactionRepository.existsById("TXN-002")).thenReturn(false);
        when(fraudClassifierService.evaluate(any())).thenReturn(Collections.emptyList());

        service.processAndPersist(event("TXN-002"));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());

        Transaction saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("TXN-002");
        assertThat(saved.getAccountId()).isEqualTo("ACC-001");
        assertThat(saved.getAmount()).isEqualTo(1_500.0);
        assertThat(saved.getMerchantCategory()).isEqualTo("RETAIL");
        assertThat(saved.getChannel()).isEqualTo("POS");
        assertThat(saved.isFlagged()).isFalse();
        assertThat(saved.getReceivedAt()).isNotNull();
    }

    // --- Happy path: fraud detected ---

    @Test
    void flagsTransaction_whenRuleFires() {
        when(transactionRepository.existsById("TXN-001")).thenReturn(false);
        when(fraudClassifierService.evaluate(any()))
                .thenReturn(List.of(new FraudFlagResult("HIGH_AMOUNT", "HIGH", "Amount exceeds threshold")));

        service.processAndPersist(event("TXN-001"));

        // Transaction should be saved twice: once before rules, once after flagging
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(txCaptor.capture());

        // The second save must have flagged=true
        Transaction secondSave = txCaptor.getAllValues().get(1);
        assertThat(secondSave.isFlagged()).isTrue();
    }

    @Test
    void savesFraudFlag_forEachFiringRule() {
        when(transactionRepository.existsById("TXN-001")).thenReturn(false);
        when(fraudClassifierService.evaluate(any())).thenReturn(List.of(
                new FraudFlagResult("HIGH_AMOUNT",    "HIGH",   "Amount too high"),
                new FraudFlagResult("UNUSUAL_HOUR",   "MEDIUM", "Late night tx"),
                new FraudFlagResult("HIGH_FREQUENCY", "HIGH",   "Burst detected")
        ));

        service.processAndPersist(event("TXN-001"));

        // One FraudFlag row saved per result
        ArgumentCaptor<FraudFlag> flagCaptor = ArgumentCaptor.forClass(FraudFlag.class);
        verify(fraudFlagRepository, times(3)).save(flagCaptor.capture());

        List<FraudFlag> savedFlags = flagCaptor.getAllValues();
        assertThat(savedFlags).extracting(FraudFlag::getRuleName)
                .containsExactlyInAnyOrder("HIGH_AMOUNT", "UNUSUAL_HOUR", "HIGH_FREQUENCY");
        assertThat(savedFlags).allMatch(f -> f.getFlaggedAt() != null);
        assertThat(savedFlags).allMatch(f -> f.getTransaction() != null);
    }

    @Test
    void doesNotSaveFraudFlag_whenNoRulesFire() {
        when(transactionRepository.existsById("TXN-001")).thenReturn(false);
        when(fraudClassifierService.evaluate(any())).thenReturn(Collections.emptyList());

        service.processAndPersist(event("TXN-001"));

        verify(fraudFlagRepository, never()).save(any());
    }
}
