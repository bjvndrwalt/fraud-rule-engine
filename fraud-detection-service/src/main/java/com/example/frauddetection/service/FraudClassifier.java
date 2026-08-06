package com.example.frauddetection.service;

import com.example.frauddetection.entity.Transaction;
import com.example.frauddetection.rules.FraudFlagResult;

import java.util.List;

/**
 * Contract for evaluating fraud rules against a transaction.
 * Injected as an interface so the service layer can be mocked in tests
 * without requiring bytecode instrumentation (needed on Java 21+).
 */
public interface FraudClassifier {
    List<FraudFlagResult> evaluate(Transaction tx);
}
