package com.example.frauddetection.rules;

import com.example.frauddetection.config.FraudRulesConfig;
import com.example.frauddetection.entity.Transaction;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@Order(4)
public class RoundAmountRule implements FraudRule {

    private final FraudRulesConfig.RoundAmountConfig config;

    public RoundAmountRule(FraudRulesConfig fraudRulesConfig) {
        this.config = fraudRulesConfig.getRoundAmount();
    }

    @Override
    public List<FraudFlagResult> evaluate(Transaction tx) {
        if (!config.isEnabled()) {
            return Collections.emptyList();
        }
        double amount = tx.getAmount();
        // Cast to long before % to avoid float precision issues (e.g. 5000.0 % 1000.0 → 0.0 is not reliable)
        if (amount < config.getMinAmount() || (long) amount % config.getMultiple() != 0) {
            return Collections.emptyList();
        }
        return List.of(new FraudFlagResult(
            "ROUND_AMOUNT",
            "LOW",
            String.format("Amount R%.0f is a round multiple of R%d", amount, config.getMultiple())
        ));
    }
}
