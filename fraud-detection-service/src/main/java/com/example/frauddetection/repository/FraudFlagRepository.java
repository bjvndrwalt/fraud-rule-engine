package com.example.frauddetection.repository;

import com.example.frauddetection.entity.FraudFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, Long> {

    List<FraudFlag> findByRuleName(String ruleName);

    List<FraudFlag> findByTransactionId(String transactionId);

    @Query("SELECT f.ruleName as ruleName, COUNT(f) as count FROM FraudFlag f GROUP BY f.ruleName")
    List<RuleCount> countGroupedByRuleName();

    interface RuleCount {
        String getRuleName();
        Long getCount();
    }
}
