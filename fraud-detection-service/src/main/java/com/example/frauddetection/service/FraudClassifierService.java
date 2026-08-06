package com.example.frauddetection.service;

import com.example.frauddetection.entity.Transaction;
import com.example.frauddetection.rules.FraudFlagResult;
import com.example.frauddetection.rules.FraudRule;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FraudClassifierService implements FraudClassifier {

    private final List<FraudRule> rules;

    public FraudClassifierService(List<FraudRule> rules) {
        this.rules = rules;
    }

    public List<FraudFlagResult> evaluate(Transaction tx) {
        return rules.stream()
                .flatMap(rule -> rule.evaluate(tx).stream())
                .collect(Collectors.toList());
    }
}
