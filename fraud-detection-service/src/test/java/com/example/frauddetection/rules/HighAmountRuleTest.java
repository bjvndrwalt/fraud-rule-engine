package com.example.frauddetection.rules;

import com.example.frauddetection.config.FraudRulesConfig;
import com.example.frauddetection.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HighAmountRuleTest {

    private FraudRulesConfig config;
    private HighAmountRule rule;

    @BeforeEach
    void setUp() {
        // Creates a config with default values: enabled=true, threshold=10000.0
        config = new FraudRulesConfig();
        rule = new HighAmountRule(config);
    }

    // --- Helper ---

    private Transaction txWithAmount(double amount) {
        Transaction tx = new Transaction();
        tx.setId("TXN-001");
        tx.setAccountId("ACC-001");
        tx.setAmount(amount);
        return tx;
    }

    // --- Tests ---

    @Test
    void fires_whenAmountExceedsThreshold() {
        List<FraudFlagResult> results = rule.evaluate(txWithAmount(15_000.0));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRuleName()).isEqualTo("HIGH_AMOUNT");
        assertThat(results.get(0).getSeverity()).isEqualTo("HIGH");
    }

    @Test
    void doesNotFire_whenAmountEqualsThreshold() {
        // threshold is strictly <, not <=
        List<FraudFlagResult> results = rule.evaluate(txWithAmount(10_000.0));

        assertThat(results).isEmpty();
    }

    @Test
    void doesNotFire_whenAmountBelowThreshold() {
        List<FraudFlagResult> results = rule.evaluate(txWithAmount(9_999.99));

        assertThat(results).isEmpty();
    }

    @Test
    void doesNotFire_whenRuleIsDisabled() {
        config.getHighAmount().setEnabled(false);

        List<FraudFlagResult> results = rule.evaluate(txWithAmount(50_000.0));

        assertThat(results).isEmpty();
    }

    @Test
    void detailMessage_containsActualAmountAndThreshold() {
        List<FraudFlagResult> results = rule.evaluate(txWithAmount(15_000.0));

        assertThat(results.get(0).getDetail())
                .contains("15000.00")
                .contains("10000.00");
    }

    @Test
    void fires_withCustomThreshold() {
        config.getHighAmount().setThreshold(50_000.0);

        // R40k should not fire with the new threshold
        assertThat(rule.evaluate(txWithAmount(40_000.0))).isEmpty();
        // R60k should fire
        assertThat(rule.evaluate(txWithAmount(60_000.0))).hasSize(1);
    }
}
