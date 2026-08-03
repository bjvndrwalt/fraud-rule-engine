package com.example.frauddetection.rules;

import com.example.frauddetection.entity.Transaction;

import java.util.List;

public interface FraudRule {
    List<FraudFlagResult> evaluate(Transaction tx);
}
