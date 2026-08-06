package com.example.frauddetection.api;

import com.example.frauddetection.entity.FraudFlag;
import com.example.frauddetection.entity.Transaction;
import com.example.frauddetection.repository.FraudFlagRepository;
import com.example.frauddetection.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionController.class)
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=localhost:9092")
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionRepository transactionRepository;

    @MockBean
    private FraudFlagRepository fraudFlagRepository;

    // --- Helpers ---

    private Transaction buildTx(String id, String accountId, double amount, boolean flagged) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setAccountId(accountId);
        tx.setAmount(amount);
        tx.setMerchantCategory("RETAIL");
        tx.setChannel("POS");
        tx.setTransactionTime(Instant.parse("2024-01-15T08:00:00Z"));
        tx.setFlagged(flagged);
        return tx;
    }

    private FraudFlag buildFlag(String txId, String ruleName, String severity) {
        FraudFlag flag = new FraudFlag();
        flag.setRuleName(ruleName);
        flag.setSeverity(severity);
        flag.setDetail("Test detail");
        flag.setFlaggedAt(Instant.parse("2024-01-15T08:00:01Z"));
        return flag;
    }

    // --- GET /api/transactions ---

    @Test
    void getTransactions_returnsPageOfTransactions() throws Exception {
        Transaction tx = buildTx("TXN-001", "ACC-001", 500.0, false);
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(tx)));

        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id", is("TXN-001")))
                .andExpect(jsonPath("$.content[0].accountId", is("ACC-001")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void getTransactions_withFlaggedFilter_callsCorrectRepository() throws Exception {
        Transaction tx = buildTx("TXN-002", "ACC-002", 15_000.0, true);
        when(transactionRepository.findByFlagged(eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(tx)));

        mockMvc.perform(get("/api/transactions").param("flagged", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].flagged", is(true)));
    }

    @Test
    void getTransactions_withAccountIdFilter_callsCorrectRepository() throws Exception {
        Transaction tx = buildTx("TXN-003", "ACC-005", 200.0, false);
        when(transactionRepository.findByAccountId(eq("ACC-005"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(tx)));

        mockMvc.perform(get("/api/transactions").param("accountId", "ACC-005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].accountId", is("ACC-005")));
    }

    @Test
    void getTransactions_withBothFilters_callsCorrectRepository() throws Exception {
        Transaction tx = buildTx("TXN-004", "ACC-003", 20_000.0, true);
        when(transactionRepository.findByFlaggedAndAccountId(eq(true), eq("ACC-003"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(tx)));

        mockMvc.perform(get("/api/transactions")
                        .param("flagged", "true")
                        .param("accountId", "ACC-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void getTransactions_returnsEmptyPage_whenNoResults() throws Exception {
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    // --- GET /api/transactions/{id} ---

    @Test
    void getTransaction_returns200WithFraudFlags_whenFound() throws Exception {
        Transaction tx = buildTx("TXN-001", "ACC-001", 15_000.0, true);
        FraudFlag flag = buildFlag("TXN-001", "HIGH_AMOUNT", "HIGH");

        when(transactionRepository.findById("TXN-001")).thenReturn(Optional.of(tx));
        when(fraudFlagRepository.findByTransactionId("TXN-001")).thenReturn(List.of(flag));

        mockMvc.perform(get("/api/transactions/TXN-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.id", is("TXN-001")))
                .andExpect(jsonPath("$.fraudFlags", hasSize(1)))
                .andExpect(jsonPath("$.fraudFlags[0].ruleName", is("HIGH_AMOUNT")));
    }

    @Test
    void getTransaction_returns200WithEmptyFlags_whenTransactionNotFlagged() throws Exception {
        Transaction tx = buildTx("TXN-001", "ACC-001", 500.0, false);

        when(transactionRepository.findById("TXN-001")).thenReturn(Optional.of(tx));
        when(fraudFlagRepository.findByTransactionId("TXN-001")).thenReturn(List.of());

        mockMvc.perform(get("/api/transactions/TXN-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraudFlags", hasSize(0)));
    }

    @Test
    void getTransaction_returns404_whenNotFound() throws Exception {
        when(transactionRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/transactions/UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    // --- GET /api/fraud-flags ---

    @Test
    void getFraudFlags_returnsAllFlags_whenNoFilter() throws Exception {
        FraudFlag f1 = buildFlag("TXN-001", "HIGH_AMOUNT",    "HIGH");
        FraudFlag f2 = buildFlag("TXN-002", "UNUSUAL_HOUR", "MEDIUM");
        when(fraudFlagRepository.findAll()).thenReturn(List.of(f1, f2));

        mockMvc.perform(get("/api/fraud-flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void getFraudFlags_filtersbyRuleName_whenParamProvided() throws Exception {
        FraudFlag flag = buildFlag("TXN-001", "HIGH_AMOUNT", "HIGH");
        when(fraudFlagRepository.findByRuleName("HIGH_AMOUNT")).thenReturn(List.of(flag));

        mockMvc.perform(get("/api/fraud-flags").param("ruleName", "HIGH_AMOUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ruleName", is("HIGH_AMOUNT")));
    }

    @Test
    void getFraudFlags_returnsEmptyList_whenNoneExist() throws Exception {
        when(fraudFlagRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/fraud-flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // --- GET /api/stats ---

    @Test
    void getStats_returnsCorrectCounts() throws Exception {
        when(transactionRepository.count()).thenReturn(100L);
        when(transactionRepository.countByFlagged(true)).thenReturn(25L);
        when(fraudFlagRepository.countGroupedByRuleName()).thenReturn(List.of(
                ruleCount("HIGH_AMOUNT", 15L),
                ruleCount("UNUSUAL_HOUR", 10L)
        ));

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions", is(100)))
                .andExpect(jsonPath("$.flaggedCount", is(25)))
                .andExpect(jsonPath("$.flaggedPercentage", is(25.0)))
                .andExpect(jsonPath("$.byRule.HIGH_AMOUNT", is(15)))
                .andExpect(jsonPath("$.byRule.UNUSUAL_HOUR", is(10)));
    }

    @Test
    void getStats_returnZeroPercentage_whenNoTransactions() throws Exception {
        when(transactionRepository.count()).thenReturn(0L);
        when(transactionRepository.countByFlagged(true)).thenReturn(0L);
        when(fraudFlagRepository.countGroupedByRuleName()).thenReturn(List.of());

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions", is(0)))
                .andExpect(jsonPath("$.flaggedPercentage", is(0.0)));
    }

    // --- Private helper for RuleCount projection ---

    private FraudFlagRepository.RuleCount ruleCount(String name, long count) {
        return new FraudFlagRepository.RuleCount() {
            public String getRuleName() { return name; }
            public Long getCount()      { return count; }
        };
    }
}
