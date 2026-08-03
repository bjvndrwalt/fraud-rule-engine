package com.example.frauddetection.rules;

public class FraudFlagResult {

    private final String ruleName;
    private final String severity;
    private final String detail;

    public FraudFlagResult(String ruleName, String severity, String detail) {
        this.ruleName = ruleName;
        this.severity = severity;
        this.detail = detail;
    }

    public String getRuleName() { return ruleName; }
    public String getSeverity() { return severity; }
    public String getDetail() { return detail; }
}
