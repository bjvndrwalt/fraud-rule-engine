package com.example.frauddetection.rules;

import com.example.frauddetection.config.FraudRulesConfig;
import com.example.frauddetection.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Default config: multiple=1000, minAmount=5000.0
 * Rule fires when: amount >= minAmount AND (long)amount % multiple == 0
 */
class RoundAmountRuleTest {

    private FraudRulesConfig config;
    private RoundAmountRule rule;

    @BeforeEach
    void setUp() {
        config = new FraudRulesConfig();
        rule = new RoundAmountRule(config);
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
    void fires_forRoundAmountAtMinimum() {
        // R5000 — exactly at minAmount and a multiple of 1000
        List<FraudFlagResult> results = rule.evaluate(txWithAmount(5_000.0));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRuleName()).isEqualTo("ROUND_AMOUNT");
        assertThat(results.get(0).getSeverity()).isEqualTo("LOW");
    }

    @Test
    void fires_forLargeRoundAmount() {
        assertThat(rule.evaluate(txWithAmount(10_000.0))).hasSize(1);
        assertThat(rule.evaluate(txWithAmount(50_000.0))).hasSize(1);
    }

    @Test
    void doesNotFire_whenAmountBelowMinimum() {
        // R1000 is a multiple of 1000 but below the R5000 minimum
        assertThat(rule.evaluate(txWithAmount(1_000.0))).isEmpty();
        assertThat(rule.evaluate(txWithAmount(4_999.0))).isEmpty();
    }

    @Test
    void doesNotFire_forNonRoundAmount_aboveMin() {
        // R5500 is above min but not a multiple of 1000
        assertThat(rule.evaluate(txWithAmount(5_500.0))).isEmpty();
        assertThat(rule.evaluate(txWithAmount(7_777.0))).isEmpty();
    }

    @Test
    void doesNotFire_whenRuleIsDisabled() {
        config.getRoundAmount().setEnabled(false);

        assertThat(rule.evaluate(txWithAmount(10_000.0))).isEmpty();
    }

    @Test
    void doesNotFire_forSlightlyOffRoundAmount() {
        // R5000.01 — above min, but (long) cast drops the decimal → 5000 % 1000 == 0 → would fire
        // BUT R5001.0 — (long)5001 % 1000 = 1 → should NOT fire
        assertThat(rule.evaluate(txWithAmount(5_001.0))).isEmpty();
        assertThat(rule.evaluate(txWithAmount(9_999.0))).isEmpty();
    }

    @Test
    void floatPrecision_handledByLongCast() {
        // Float modulo is unreliable (5000.0 % 1000.0 can be 0.9999... in some runtimes).
        // The rule casts to (long) first — this test confirms it works correctly.
        assertThat(rule.evaluate(txWithAmount(5_000.0))).hasSize(1);
        assertThat(rule.evaluate(txWithAmount(15_000.0))).hasSize(1);
    }

    @Test
    void detailMessage_containsAmountAndMultiple() {
        List<FraudFlagResult> results = rule.evaluate(txWithAmount(5_000.0));

        assertThat(results.get(0).getDetail())
                .contains("5000")
                .contains("1000");
    }

    @Test
    void fires_withCustomMultipleAndMinAmount() {
        config.getRoundAmount().setMultiple(500);
        config.getRoundAmount().setMinAmount(2_500.0);

        assertThat(rule.evaluate(txWithAmount(2_500.0))).hasSize(1);
        assertThat(rule.evaluate(txWithAmount(2_499.0))).isEmpty();
        assertThat(rule.evaluate(txWithAmount(2_750.0))).isEmpty(); // not a multiple of 500
    }
}
