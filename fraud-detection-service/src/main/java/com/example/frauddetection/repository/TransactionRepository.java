package com.example.frauddetection.repository;

import com.example.frauddetection.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    Page<Transaction> findByFlagged(boolean flagged, Pageable pageable);

    Page<Transaction> findByAccountId(String accountId, Pageable pageable);

    Page<Transaction> findByFlaggedAndAccountId(boolean flagged, String accountId, Pageable pageable);

    long countByFlagged(boolean flagged);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.accountId = :accountId AND t.transactionTime > :since")
    long countRecentTransactions(@Param("accountId") String accountId, @Param("since") Instant since);
}
