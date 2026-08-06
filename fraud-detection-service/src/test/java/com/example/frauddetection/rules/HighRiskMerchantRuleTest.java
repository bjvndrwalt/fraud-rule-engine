package com.example.frauddetection.rules;

import com.example.frauddetection.config.FraudRulesConfig;
import com.example.frauddetection.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HighRiskMerchantRuleTest {

    private FraudRulesConfig config;
    private HighRiskMerchantRule rule;

    @BeforeEach
    void setUp() {
        // Default categories: [GAMBLING, CRYPTO, FOREX]
        config = new FraudRulesConfig();
        rule = new HighRiskMerchantRule(config);
    }

    // --- Helper ---

    private Transaction txWithCategory(String category) {
        Transaction tx = new Transaction();
        tx.setId("TXN-001");
        tx.setAccountId("ACC-001");
        tx.setAmount(500.0);
        tx.setMerchantCategory(category);
        return tx;
    }

    // --- Tests ---

    @ParameterizedTest
    @ValueSource(strings = {"GAMBLING", "CRYPTO", "FOREX"})
    void fires_forEachHighRiskCategory(String category) {
        List<FraudFlagResult> results = rule.evaluate(txWithCategory(category));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRuleName()).isEqualTo("HIGH_RISK_MERCHANT");
        assertThat(results.get(0).getSeverity()).isEqualTo("HIGH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETAIL", "GROCERY", "FUEL", "RESTAURANT", "TRAVEL"})
    void doesNotFire_forSafeCategories(String category) {
        assertThat(rule.evaluate(txWithCategory(category))).isEmpty();
    }

    @Test
    void fires_forLowercaseInput_becauseRuleUpperCases() {
        // The rule calls toUpperCase() on the category before checking
        List<FraudFlagResult> results = rule.evaluate(txWithCategory("gambling"));

        assertThat(results).hasSize(1);
    }

    @Test
    void doesNotFire_whenMerchantCategoryIsNull() {
        assertThat(rule.evaluate(txWithCategory(null))).isEmpty();
    }

    @Test
    void doesNotFire_whenRuleIsDisabled() {
        config.getHighRiskMerchant().setEnabled(false);

        assertThat(rule.evaluate(txWithCategory("GAMBLING"))).isEmpty();
    }

    @Test
    void detailMessage_containsCategory() {
        List<FraudFlagResult> results = rule.evaluate(txWithCategory("CRYPTO"));

        assertThat(results.get(0).getDetail()).contains("CRYPTO");
    }

    @Test
    void fires_afterAddingCustomCategory() {
        config.getHighRiskMerchant().getCategories().add("WEAPONS");

        assertThat(rule.evaluate(txWithCategory("WEAPONS"))).hasSize(1);
    }
}
