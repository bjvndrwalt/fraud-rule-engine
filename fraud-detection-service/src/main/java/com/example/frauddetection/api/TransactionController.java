package com.example.frauddetection.api;

import com.example.frauddetection.entity.FraudFlag;
import com.example.frauddetection.entity.Transaction;
import com.example.frauddetection.repository.FraudFlagRepository;
import com.example.frauddetection.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionRepository transactionRepository;
    private final FraudFlagRepository fraudFlagRepository;

    public TransactionController(TransactionRepository transactionRepository,
                                  FraudFlagRepository fraudFlagRepository) {
        this.transactionRepository = transactionRepository;
        this.fraudFlagRepository = fraudFlagRepository;
    }

    @GetMapping("/transactions")
    public Page<Transaction> getTransactions(
            @RequestParam Optional<Boolean> flagged,
            @RequestParam Optional<String> accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "transactionTime"));

        if (flagged.isPresent() && accountId.isPresent()) {
            return transactionRepository.findByFlaggedAndAccountId(flagged.get(), accountId.get(), pageable);
        } else if (flagged.isPresent()) {
            return transactionRepository.findByFlagged(flagged.get(), pageable);
        } else if (accountId.isPresent()) {
            return transactionRepository.findByAccountId(accountId.get(), pageable);
        }
        return transactionRepository.findAll(pageable);
    }

    @GetMapping("/transactions/{id}")
    public ResponseEntity<Map<String, Object>> getTransaction(@PathVariable String id) {
        return transactionRepository.findById(id)
                .map(tx -> {
                    List<FraudFlag> flags = fraudFlagRepository.findByTransactionId(id);
                    Map<String, Object> response = new HashMap<>();
                    response.put("transaction", tx);
                    response.put("fraudFlags", flags);
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/fraud-flags")
    public List<FraudFlag> getFraudFlags(@RequestParam Optional<String> ruleName) {
        return ruleName.map(fraudFlagRepository::findByRuleName)
                .orElseGet(fraudFlagRepository::findAll);
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        long total = transactionRepository.count();
        long flagged = transactionRepository.countByFlagged(true);

        Map<String, Long> byRule = new HashMap<>();
        for (FraudFlagRepository.RuleCount rc : fraudFlagRepository.countGroupedByRuleName()) {
            byRule.put(rc.getRuleName(), rc.getCount());
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTransactions", total);
        stats.put("flaggedCount", flagged);
        stats.put("flaggedPercentage", total == 0 ? 0.0 : Math.round((flagged * 100.0 / total) * 10.0) / 10.0);
        stats.put("byRule", byRule);
        return stats;
    }
}
