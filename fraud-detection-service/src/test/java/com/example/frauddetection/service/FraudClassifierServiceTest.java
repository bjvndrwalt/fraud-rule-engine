package com.example.frauddetection.service;

import com.example.frauddetection.entity.Transaction;
import com.example.frauddetection.rules.FraudFlagResult;
import com.example.frauddetection.rules.FraudRule;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FraudClassifierServiceTest {

    // --- Tests ---

    @Test
    void returnsEmptyList_whenNoRulesFire() {
        FraudRule noOp = tx -> Collections.emptyList();
        FraudClassifierService service = new FraudClassifierService(List.of(noOp));

        assertThat(service.evaluate(new Transaction())).isEmpty();
    }

    @Test
    void aggregatesResults_fromMultipleRulesThatFire() {
        FraudRule ruleA = tx -> List.of(new FraudFlagResult("RULE_A", "HIGH", "detail A"));
        FraudRule ruleB = tx -> List.of(new FraudFlagResult("RULE_B", "LOW",  "detail B"));

        FraudClassifierService service = new FraudClassifierService(List.of(ruleA, ruleB));
        List<FraudFlagResult> results = service.evaluate(new Transaction());

        assertThat(results).hasSize(2);
        assertThat(results).extracting(FraudFlagResult::getRuleName)
                .containsExactly("RULE_A", "RULE_B");
    }

    @Test
    void flattensResults_whenSomeRulesReturnEmpty() {
        FraudRule fires  = tx -> List.of(new FraudFlagResult("FIRES", "HIGH", "detail"));
        FraudRule silent = tx -> Collections.emptyList();

        FraudClassifierService service = new FraudClassifierService(List.of(fires, silent, fires));
        List<FraudFlagResult> results = service.evaluate(new Transaction());

        // Two rules fired, one was silent — flatMap should give exactly 2 results
        assertThat(results).hasSize(2);
    }

    @Test
    void callsEveryRule_regardlessOfPreviousResults() {
        // Use mocked rules so we can verify each was called
        FraudRule ruleA = mock(FraudRule.class);
        FraudRule ruleB = mock(FraudRule.class);
        when(ruleA.evaluate(any())).thenReturn(Collections.emptyList());
        when(ruleB.evaluate(any())).thenReturn(Collections.emptyList());

        FraudClassifierService service = new FraudClassifierService(List.of(ruleA, ruleB));
        Transaction tx = new Transaction();
        service.evaluate(tx);

        // Both rules must be evaluated — no short-circuit
        verify(ruleA).evaluate(tx);
        verify(ruleB).evaluate(tx);
    }

    @Test
    void returnsEmptyList_whenRuleListIsEmpty() {
        FraudClassifierService service = new FraudClassifierService(Collections.emptyList());

        assertThat(service.evaluate(new Transaction())).isEmpty();
    }

    @Test
    void passesTheSameTransaction_toEveryRule() {
        Transaction tx = new Transaction();
        tx.setId("TXN-XYZ");

        FraudRule capturer = mock(FraudRule.class);
        when(capturer.evaluate(tx)).thenReturn(Collections.emptyList());

        FraudClassifierService service = new FraudClassifierService(List.of(capturer));
        service.evaluate(tx);

        verify(capturer).evaluate(tx);
    }
}
