package com.example.frauddetection.rules;

import com.example.frauddetection.config.FraudRulesConfig;
import com.example.frauddetection.entity.Transaction;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@Order(1)
public class HighAmountRule implements FraudRule {

    private final FraudRulesConfig.HighAmountConfig config;

    public HighAmountRule(FraudRulesConfig fraudRulesConfig) {
        this.config = fraudRulesConfig.getHighAmount();
    }

    @Override
    public List<FraudFlagResult> evaluate(Transaction tx) {
        if (!config.isEnabled() || tx.getAmount() <= config.getThreshold()) {
            return Collections.emptyList();
        }
        return List.of(new FraudFlagResult(
            "HIGH_AMOUNT",
            "HIGH",
            String.format("Amount R%.2f exceeds threshold R%.2f", tx.getAmount(), config.getThreshold())
        ));
    }
}
