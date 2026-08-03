package com.example.frauddetection.rules;

import com.example.frauddetection.config.FraudRulesConfig;
import com.example.frauddetection.entity.Transaction;
import com.example.frauddetection.repository.TransactionRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

@Component
@Order(5)
public class HighFrequencyRule implements FraudRule {

    private final FraudRulesConfig.HighFrequencyConfig config;
    private final TransactionRepository transactionRepository;

    public HighFrequencyRule(FraudRulesConfig fraudRulesConfig, TransactionRepository transactionRepository) {
        this.config = fraudRulesConfig.getHighFrequency();
        this.transactionRepository = transactionRepository;
    }

    @Override
    public List<FraudFlagResult> evaluate(Transaction tx) {
        if (!config.isEnabled()) {
            return Collections.emptyList();
        }
        Instant windowStart = Instant.now().minus(config.getWindowMinutes(), ChronoUnit.MINUTES);
        // tx is already persisted before this rule runs, so the count includes the current transaction
        long count = transactionRepository.countRecentTransactions(tx.getAccountId(), windowStart);
        if (count < config.getMaxCount()) {
            return Collections.emptyList();
        }
        return List.of(new FraudFlagResult(
            "HIGH_FREQUENCY",
            "HIGH",
            String.format("%d transactions from account %s in last %d minutes (max %d)",
                count, tx.getAccountId(), config.getWindowMinutes(), config.getMaxCount())
        ));
    }
}
