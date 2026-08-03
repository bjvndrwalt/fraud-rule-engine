package com.example.frauddetection.rules;

import com.example.frauddetection.config.FraudRulesConfig;
import com.example.frauddetection.entity.Transaction;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

@Component
@Order(2)
public class UnusualHourRule implements FraudRule {

    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    private final FraudRulesConfig.UnusualHourConfig config;

    public UnusualHourRule(FraudRulesConfig fraudRulesConfig) {
        this.config = fraudRulesConfig.getUnusualHour();
    }

    @Override
    public List<FraudFlagResult> evaluate(Transaction tx) {
        if (!config.isEnabled() || tx.getTransactionTime() == null) {
            return Collections.emptyList();
        }
        int hour = tx.getTransactionTime().atZone(SAST).getHour();
        if (hour < config.getStartHour() || hour >= config.getEndHour()) {
            return Collections.emptyList();
        }
        return List.of(new FraudFlagResult(
            "UNUSUAL_HOUR",
            "MEDIUM",
            String.format("Transaction at %02d:xx SAST — outside normal hours (%02d:00–%02d:00)",
                hour, config.getStartHour(), config.getEndHour())
        ));
    }
}
