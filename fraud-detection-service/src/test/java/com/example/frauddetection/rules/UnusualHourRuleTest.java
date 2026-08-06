package com.example.frauddetection.rules;

import com.example.frauddetection.config.FraudRulesConfig;
import com.example.frauddetection.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAST = UTC+2.  All Instants below are chosen so that their SAST hour is predictable:
 *   00:30 SAST → 2024-01-14T22:30:00Z  (hour = 0  — start boundary, inside window)
 *   02:00 SAST → 2024-01-15T00:00:00Z  (hour = 2  — mid window)
 *   03:59 SAST → 2024-01-15T01:59:00Z  (hour = 3  — last hour in window)
 *   04:00 SAST → 2024-01-15T02:00:00Z  (hour = 4  — exclusive end, outside window)
 *   10:00 SAST → 2024-01-15T08:00:00Z  (hour = 10 — normal business hours)
 */
class UnusualHourRuleTest {

    // SAST hour 0  (00:30 SAST = 22:30 UTC prev day)
    private static final Instant SAST_HOUR_0 = Instant.parse("2024-01-14T22:30:00Z");
    // SAST hour 2  (02:00 SAST = 00:00 UTC)
    private static final Instant SAST_HOUR_2 = Instant.parse("2024-01-15T00:00:00Z");
    // SAST hour 3  (03:59 SAST = 01:59 UTC)
    private static final Instant SAST_HOUR_3 = Instant.parse("2024-01-15T01:59:00Z");
    // SAST hour 4  (04:00 SAST = 02:00 UTC) — exclusive end, should NOT fire
    private static final Instant SAST_HOUR_4 = Instant.parse("2024-01-15T02:00:00Z");
    // SAST hour 10 (10:00 SAST = 08:00 UTC) — normal hours
    private static final Instant SAST_HOUR_10 = Instant.parse("2024-01-15T08:00:00Z");

    private FraudRulesConfig config;
    private UnusualHourRule rule;

    @BeforeEach
    void setUp() {
        // Default window: startHour=0, endHour=4
        config = new FraudRulesConfig();
        rule = new UnusualHourRule(config);
    }

    // --- Helper ---

    private Transaction txAt(Instant time) {
        Transaction tx = new Transaction();
        tx.setId("TXN-001");
        tx.setAccountId("ACC-001");
        tx.setAmount(500.0);
        tx.setTransactionTime(time);
        return tx;
    }

    // --- Tests ---

    @Test
    void fires_atStartOfWindow_hour0() {
        List<FraudFlagResult> results = rule.evaluate(txAt(SAST_HOUR_0));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRuleName()).isEqualTo("UNUSUAL_HOUR");
        assertThat(results.get(0).getSeverity()).isEqualTo("MEDIUM");
    }

    @Test
    void fires_midWindow_hour2() {
        assertThat(rule.evaluate(txAt(SAST_HOUR_2))).hasSize(1);
    }

    @Test
    void fires_lastHourInWindow_hour3() {
        assertThat(rule.evaluate(txAt(SAST_HOUR_3))).hasSize(1);
    }

    @Test
    void doesNotFire_atExclusiveEndOfWindow_hour4() {
        // endHour is exclusive: hour >= endHour means outside window
        assertThat(rule.evaluate(txAt(SAST_HOUR_4))).isEmpty();
    }

    @Test
    void doesNotFire_duringNormalHours_hour10() {
        assertThat(rule.evaluate(txAt(SAST_HOUR_10))).isEmpty();
    }

    @Test
    void doesNotFire_whenTransactionTimeIsNull() {
        Transaction tx = txAt(null);

        assertThat(rule.evaluate(tx)).isEmpty();
    }

    @Test
    void doesNotFire_whenRuleIsDisabled() {
        config.getUnusualHour().setEnabled(false);

        assertThat(rule.evaluate(txAt(SAST_HOUR_2))).isEmpty();
    }

    @Test
    void detailMessage_containsHourAndWindowBounds() {
        List<FraudFlagResult> results = rule.evaluate(txAt(SAST_HOUR_2));

        assertThat(results.get(0).getDetail())
                .contains("02")   // the SAST hour
                .contains("00")   // startHour
                .contains("04");  // endHour
    }

    @Test
    void fires_withCustomWindow() {
        // Shift window to 22:00–23:59 SAST
        config.getUnusualHour().setStartHour(22);
        config.getUnusualHour().setEndHour(24);

        // 22:30 SAST = 20:30 UTC
        Instant lateSAST = Instant.parse("2024-01-15T20:30:00Z");
        assertThat(rule.evaluate(txAt(lateSAST))).hasSize(1);

        // 10:00 SAST should not fire under this window
        assertThat(rule.evaluate(txAt(SAST_HOUR_10))).isEmpty();
    }
}
