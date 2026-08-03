package com.example.frauddetection.rules;

import com.example.frauddetection.config.FraudRulesConfig;
import com.example.frauddetection.entity.Transaction;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@Order(3)
public class HighRiskMerchantRule implements FraudRule {

    private final FraudRulesConfig.HighRiskMerchantConfig config;

    public HighRiskMerchantRule(FraudRulesConfig fraudRulesConfig) {
        this.config = fraudRulesConfig.getHighRiskMerchant();
    }

    @Override
    public List<FraudFlagResult> evaluate(Transaction tx) {
        if (!config.isEnabled() || tx.getMerchantCategory() == null) {
            return Collections.emptyList();
        }
        String category = tx.getMerchantCategory().toUpperCase();
        if (!config.getCategories().contains(category)) {
            return Collections.emptyList();
        }
        return List.of(new FraudFlagResult(
            "HIGH_RISK_MERCHANT",
            "HIGH",
            String.format("Merchant category %s is high-risk", category)
        ));
    }
}
