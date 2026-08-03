package com.example.frauddetection.model;

import java.time.Instant;

public class TransactionCreatedEvent {

    private String transactionId;
    private String accountId;
    private double amount;
    private String merchantCategory;
    private String merchantId;
    private String channel;
    private Instant transactionTime;

    public TransactionCreatedEvent() {}

    public TransactionCreatedEvent(String transactionId, String accountId, double amount,
                                   String merchantCategory, String merchantId, String channel,
                                   Instant transactionTime) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
        this.merchantCategory = merchantCategory;
        this.merchantId = merchantId;
        this.channel = channel;
        this.transactionTime = transactionTime;
    }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    public String getMerchantCategory() { return merchantCategory; }
    public void setMerchantCategory(String merchantCategory) { this.merchantCategory = merchantCategory; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public Instant getTransactionTime() { return transactionTime; }
    public void setTransactionTime(Instant transactionTime) { this.transactionTime = transactionTime; }

    @Override
    public String toString() {
        return "TransactionCreatedEvent{transactionId='" + transactionId + "', accountId='" + accountId +
               "', amount=" + amount + ", merchantCategory='" + merchantCategory +
               "', channel='" + channel + "', transactionTime=" + transactionTime + "}";
    }
}
