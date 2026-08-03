package com.example.frauddetection.service;

import com.example.frauddetection.entity.FraudFlag;
import com.example.frauddetection.entity.Transaction;
import com.example.frauddetection.model.TransactionCreatedEvent;
import com.example.frauddetection.repository.FraudFlagRepository;
import com.example.frauddetection.repository.TransactionRepository;
import com.example.frauddetection.rules.FraudFlagResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class TransactionPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TransactionPersistenceService.class);

    private final TransactionRepository transactionRepository;
    private final FraudFlagRepository fraudFlagRepository;
    private final FraudClassifierService fraudClassifierService;

    public TransactionPersistenceService(TransactionRepository transactionRepository,
                                         FraudFlagRepository fraudFlagRepository,
                                         FraudClassifierService fraudClassifierService) {
        this.transactionRepository = transactionRepository;
        this.fraudFlagRepository = fraudFlagRepository;
        this.fraudClassifierService = fraudClassifierService;
    }

    @Transactional
    public void processAndPersist(TransactionCreatedEvent event) {
        if (transactionRepository.existsById(event.getTransactionId())) {
            log.info("Duplicate event skipped: {}", event.getTransactionId());
            return;
        }

        Transaction tx = new Transaction();
        tx.setId(event.getTransactionId());
        tx.setAccountId(event.getAccountId());
        tx.setAmount(event.getAmount());
        tx.setMerchantCategory(event.getMerchantCategory());
        tx.setMerchantId(event.getMerchantId());
        tx.setChannel(event.getChannel());
        tx.setTransactionTime(event.getTransactionTime());
        tx.setReceivedAt(Instant.now());
        tx.setFlagged(false);

        // Persist before rule evaluation so HIGH_FREQUENCY count includes this transaction
        transactionRepository.save(tx);

        List<FraudFlagResult> results = fraudClassifierService.evaluate(tx);

        if (!results.isEmpty()) {
            tx.setFlagged(true);
            transactionRepository.save(tx);

            for (FraudFlagResult result : results) {
                FraudFlag flag = new FraudFlag();
                flag.setTransaction(tx);
                flag.setRuleName(result.getRuleName());
                flag.setSeverity(result.getSeverity());
                flag.setDetail(result.getDetail());
                flag.setFlaggedAt(Instant.now());
                fraudFlagRepository.save(flag);
                log.info("FLAGGED [{}] tx={} account={} — {}", result.getSeverity(),
                        tx.getId(), tx.getAccountId(), result.getDetail());
            }
        }
    }
}
