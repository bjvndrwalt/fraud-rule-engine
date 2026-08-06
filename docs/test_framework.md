# Test Framework

## Overview

The fraud detection service uses **JUnit 5** with **Mockito** and **AssertJ** for unit and web-layer tests. All tests live under `src/test/` and run with `mvn test`. There are no integration tests that require a running Kafka broker or real database — every external dependency is either mocked or handled by the Spring test slice.

**70 tests across 8 test classes — all passing.**

---

## Tools and Libraries

| Library | Role |
|---|---|
| **JUnit 5** (`junit-jupiter`) | Test runner and lifecycle annotations (`@Test`, `@BeforeEach`, `@ParameterizedTest`) |
| **Mockito 5** | Creates mock objects, stubs return values, verifies method calls |
| **AssertJ** | Fluent, readable assertions (`assertThat(...).hasSize(1).contains(...)`) |
| **Spring MockMvc** | Fires real HTTP requests against the controller without starting a server |
| **`spring-boot-starter-test`** | Bundles all of the above — one dependency in `pom.xml` covers everything |

All dependencies are pulled in by the single `spring-boot-starter-test` entry in `pom.xml` with `scope=test`, meaning none of this ships in the production jar.

---

## Test Strategies Used

### 1. Pure Unit Tests (Rule classes, `FraudClassifierService`)
No Spring context. Tests construct classes directly with `new`, set config values on plain `FraudRulesConfig` objects, and call `evaluate()`. Fast — typically under 10ms per class. No `@SpringBootTest`, no database, no Kafka.

### 2. Mockito Unit Tests (`TransactionPersistenceService`)
Uses `@ExtendWith(MockitoExtension.class)`. Mockito creates mock objects for the three dependencies (`TransactionRepository`, `FraudFlagRepository`, `FraudClassifier`). Tests stub what the mocks return with `when(...).thenReturn(...)` and verify what was called with `verify(...)`. No Spring context needed.

### 3. Web Layer Slice Test (`TransactionController`)
Uses `@WebMvcTest(TransactionController.class)`. Spring loads **only** the web layer (the controller + Spring MVC wiring). Nothing else — no JPA, no Kafka, no service beans. The two repository dependencies are replaced with `@MockBean` instances. Tests send HTTP requests via `MockMvc` and assert on status codes and JSON paths.

---

## Java 26 + Mockito — Why a `FraudClassifier` Interface Exists

Mockito can mock objects in two ways:
- **Interface mocking** — uses JDK's built-in `Proxy` mechanism. No bytecode changes needed. Works on any JVM.
- **Concrete class mocking** — uses a bytecode instrumentation agent to subclass the target at runtime. Requires dynamic agent loading.

Java 21 introduced restrictions on dynamic agent loading; Java 26 enforces them strictly. `FraudClassifierService` is a concrete class, so Mockito could not mock it directly on this JVM.

The fix was to extract a `FraudClassifier` interface:

```java
// service/FraudClassifier.java
public interface FraudClassifier {
    List<FraudFlagResult> evaluate(Transaction tx);
}
```

`FraudClassifierService` implements it, and `TransactionPersistenceService` depends on the interface. In tests, Mockito mocks the interface via JDK proxy — no agent needed. This is also better design: depend on abstractions, not concrete classes.

---

## Test Configuration

`src/test/resources/application.properties` overrides the main config for tests:

```properties
spring.kafka.bootstrap-servers=localhost:9092   # prevents Kafka connection attempts
spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared  # in-memory DB for web slice
spring.jpa.hibernate.ddl-auto=create-drop       # fresh schema per test run
```

`pom.xml` Surefire plugin adds `-XX:+EnableDynamicAgentLoading -Xshare:off` JVM args. This is a belt-and-suspenders precaution for Mockito on Java 21+ — the interface extraction above is the real fix, but the flags suppress noisy JVM warnings.

---

## Test Class Breakdown

---

### `HighAmountRuleTest`
**File:** `rules/HighAmountRuleTest.java` | **Tests:** 6

Tests the `HighAmountRule` which fires when `amount > threshold` (default R10,000).

**Setup:** Creates a `FraudRulesConfig` with defaults, constructs `HighAmountRule` directly. A `txWithAmount(double)` helper builds minimal `Transaction` objects.

| Test | What it checks |
|---|---|
| `fires_whenAmountExceedsThreshold` | R15,000 triggers the rule; result has ruleName `HIGH_AMOUNT` and severity `HIGH` |
| `doesNotFire_whenAmountEqualsThreshold` | R10,000 is `<=` threshold — the condition is strictly greater-than |
| `doesNotFire_whenAmountBelowThreshold` | R9,999.99 produces an empty result |
| `doesNotFire_whenRuleIsDisabled` | Setting `enabled=false` short-circuits the rule before any amount check |
| `detailMessage_containsActualAmountAndThreshold` | The detail string includes both R15,000.00 and R10,000.00 |
| `fires_withCustomThreshold` | Raising the threshold to R50,000 means R40k passes but R60k flags — confirms the config is live |

---

### `UnusualHourRuleTest`
**File:** `rules/UnusualHourRuleTest.java` | **Tests:** 9

Tests the `UnusualHourRule` which fires when the transaction hour in **SAST (UTC+2)** falls within `[startHour, endHour)` (default 00:00–04:00).

**Setup:** Six `static final Instant` constants are declared at the top of the class, each chosen so that its SAST hour is exact and predictable:

```
SAST_HOUR_0  = 2024-01-14T22:30:00Z  →  00:30 SAST  (inside window)
SAST_HOUR_2  = 2024-01-15T00:00:00Z  →  02:00 SAST  (inside window)
SAST_HOUR_3  = 2024-01-15T01:59:00Z  →  03:59 SAST  (inside window)
SAST_HOUR_4  = 2024-01-15T02:00:00Z  →  04:00 SAST  (exclusive end — outside)
SAST_HOUR_10 = 2024-01-15T08:00:00Z  →  10:00 SAST  (normal hours)
```

| Test | What it checks |
|---|---|
| `fires_atStartOfWindow_hour0` | Hour 0 is the inclusive start boundary |
| `fires_midWindow_hour2` | Mid-window fires; result has severity `MEDIUM` |
| `fires_lastHourInWindow_hour3` | Hour 3 is the last valid hour |
| `doesNotFire_atExclusiveEndOfWindow_hour4` | `hour >= endHour` means 4am is outside the window |
| `doesNotFire_duringNormalHours_hour10` | 10am is safe |
| `doesNotFire_whenTransactionTimeIsNull` | Null guard — rule returns empty rather than throwing NPE |
| `doesNotFire_whenRuleIsDisabled` | Kill-switch respected |
| `detailMessage_containsHourAndWindowBounds` | Detail includes the transaction hour and both window boundaries |
| `fires_withCustomWindow` | Shifting window to 22:00–24:00 SAST confirms the config is used at evaluation time |

---

### `HighRiskMerchantRuleTest`
**File:** `rules/HighRiskMerchantRuleTest.java` | **Tests:** 13

Tests the `HighRiskMerchantRule` which fires when `merchantCategory` is in `[GAMBLING, CRYPTO, FOREX]`.

**Key technique:** Uses `@ParameterizedTest` with `@ValueSource` to run the same assertion across multiple inputs without duplicating test methods.

| Test | What it checks |
|---|---|
| `fires_forEachHighRiskCategory` *(×3 parametrized)* | GAMBLING, CRYPTO, FOREX each produce a result with severity `HIGH` |
| `doesNotFire_forSafeCategories` *(×5 parametrized)* | RETAIL, GROCERY, FUEL, RESTAURANT, TRAVEL are all safe |
| `fires_forLowercaseInput_becauseRuleUpperCases` | The rule calls `.toUpperCase()` — `"gambling"` is correctly caught |
| `doesNotFire_whenMerchantCategoryIsNull` | Null guard prevents NPE |
| `doesNotFire_whenRuleIsDisabled` | Kill-switch respected |
| `detailMessage_containsCategory` | Detail string includes the category name |
| `fires_afterAddingCustomCategory` | Mutating `getCategories().add("WEAPONS")` is immediately effective — proves the list is live, not a copy |

---

### `RoundAmountRuleTest`
**File:** `rules/RoundAmountRuleTest.java` | **Tests:** 9

Tests the `RoundAmountRule` which fires when `amount >= minAmount` AND `(long)amount % multiple == 0` (defaults: multiple=1000, minAmount=R5,000).

**Key test — float precision:** The rule casts `amount` to `long` before the modulo check. This is intentional: floating-point modulo (`5000.0 % 1000.0`) can produce results like `0.9999...` due to IEEE 754 representation. The `floatPrecision_handledByLongCast` test confirms the cast works correctly.

| Test | What it checks |
|---|---|
| `fires_forRoundAmountAtMinimum` | R5,000 — exactly at minAmount and a multiple of 1,000 |
| `fires_forLargeRoundAmount` | R10,000 and R50,000 both fire |
| `doesNotFire_whenAmountBelowMinimum` | R1,000 is a round multiple but below the minimum |
| `doesNotFire_forNonRoundAmount_aboveMin` | R5,500 and R7,777 are above min but not round |
| `doesNotFire_whenRuleIsDisabled` | Kill-switch respected |
| `doesNotFire_forSlightlyOffRoundAmount` | R5,001 and R9,999 — confirm the `%` check is strict |
| `floatPrecision_handledByLongCast` | Confirms R5,000.0 and R15,000.0 are detected despite floating-point representation |
| `detailMessage_containsAmountAndMultiple` | Detail includes both the amount and the multiple |
| `fires_withCustomMultipleAndMinAmount` | Reconfiguring to multiple=500, min=R2,500 works correctly |

---

### `HighFrequencyRuleTest`
**File:** `rules/HighFrequencyRuleTest.java` | **Tests:** 7

Tests the `HighFrequencyRule` which fires when `countRecentTransactions(accountId, windowStart) >= maxCount` (default: 5 in 10 minutes).

**Why Mockito is needed here:** This is the only rule that queries the database. `TransactionRepository.countRecentTransactions()` is mocked so tests can simulate any count without touching SQLite.

**Key technique — `ArgumentCaptor`:** The `queuesWindowStart_approximately_nowMinusWindowMinutes` test captures the `Instant` that was passed to the repository and asserts it falls within a 4-second window of `now - 10 minutes`. This confirms the window calculation is correct without hardcoding times.

| Test | What it checks |
|---|---|
| `fires_whenCountReachesMaxCount` | Count of exactly 5 fires the rule (threshold is `>=`, not `>`) |
| `fires_whenCountExceedsMaxCount` | Count of 10 also fires |
| `doesNotFire_whenCountBelowMaxCount` | Count of 4 returns empty |
| `doesNotFire_whenRuleIsDisabled` | Kill-switch respected; also verifies the repository is **never called** (saves a DB round-trip) |
| `queuesWindowStart_approximately_nowMinusWindowMinutes` | `ArgumentCaptor` confirms the window start timestamp is within 4 seconds of the expected value |
| `detailMessage_containsCountAccountAndWindow` | Detail includes count, account ID, and window in minutes |
| `fires_withCustomThresholdAndWindow` | Reconfiguring to maxCount=3, windowMinutes=5 is respected |

---

### `FraudClassifierServiceTest`
**File:** `service/FraudClassifierServiceTest.java` | **Tests:** 6

Tests the `FraudClassifierService` orchestrator in isolation. Because `FraudRule` is an interface, rules are implemented inline as lambdas — no mocking library needed.

**What it tests:** The service's job is to call all rules and `flatMap` their results into one list. These tests verify the orchestration logic, not the rules themselves.

| Test | What it checks |
|---|---|
| `returnsEmptyList_whenNoRulesFire` | Single no-op rule produces empty list |
| `aggregatesResults_fromMultipleRulesThatFire` | Two firing rules produce 2 combined results in order |
| `flattensResults_whenSomeRulesReturnEmpty` | Two firing rules + one silent rule → exactly 2 results (empty lists are correctly dropped by `flatMap`) |
| `callsEveryRule_regardlessOfPreviousResults` | Both mocked rules are `verify()`'d — confirms there is no short-circuit on first match |
| `returnsEmptyList_whenRuleListIsEmpty` | Empty rule list → empty result (no NPE) |
| `passesTheSameTransaction_toEveryRule` | The identical `Transaction` object is passed to every rule — not a copy |

---

### `TransactionPersistenceServiceTest`
**File:** `service/TransactionPersistenceServiceTest.java` | **Tests:** 7

Tests the full processing pipeline inside `processAndPersist()`. All three dependencies are mocked with `@Mock` and injected via `@InjectMocks`.

**Why `FraudClassifier` (interface) and not `FraudClassifierService` (class):** Mockito mocks interfaces via JDK proxies, which requires no bytecode manipulation. On Java 21+, dynamic agent loading is restricted — mocking concrete classes fails. Depending on the `FraudClassifier` interface is both the correct design choice and the fix.

**Key technique — `inOrder`:** The `transaction_isSavedBeforeRulesRun` test uses Mockito's `inOrder()` to verify that `transactionRepository.save()` happens **before** `fraudClassifier.evaluate()`. This is critical for `HighFrequencyRule` — if the order were reversed, the current transaction would not be in the DB when the frequency count runs.

| Test | What it checks |
|---|---|
| `skips_duplicateTransaction` | When `existsById` returns true, `save()` and `evaluate()` are never called |
| `saves_newTransaction_whenNothingFlagged` | New transaction with no rules firing → exactly one `save()` call |
| `transaction_isSavedBeforeRulesRun` | `inOrder` verifies save → evaluate ordering |
| `transaction_mappedCorrectly_fromEvent` | `ArgumentCaptor` captures the saved `Transaction` and asserts every field matches the incoming `TransactionCreatedEvent` |
| `flagsTransaction_whenRuleFires` | When a rule fires, `save()` is called twice and the second call has `flagged=true` |
| `savesFraudFlag_forEachFiringRule` | Three rules firing → three `FraudFlag` rows saved, each with correct `ruleName` and non-null `flaggedAt` |
| `doesNotSaveFlag_whenNoRulesFire` | `fraudFlagRepository.save()` is never called when no rules fire |

---

### `TransactionControllerTest`
**File:** `api/TransactionControllerTest.java` | **Tests:** 13

Tests all four REST endpoints using `@WebMvcTest`. Spring loads only the web layer; both repositories are `@MockBean`. Tests use `MockMvc` to fire real HTTP requests and `jsonPath` to assert on the JSON response body.

**`@WebMvcTest` vs `@SpringBootTest`:** `@SpringBootTest` would load the entire application context — including Kafka consumers and JPA, which would need real infrastructure. `@WebMvcTest` loads only the controller and Spring MVC machinery. It's faster, simpler, and tests exactly what it should: the HTTP layer.

**`jsonPath` syntax:** `$.content[0].id` means: root object (`$`) → `content` array → first element (`[0]`) → `id` field. `$` by itself is the root of a JSON array.

#### `GET /api/transactions`

| Test | What it checks |
|---|---|
| `getTransactions_returnsPageOfTransactions` | Default call returns a page with `content`, `totalElements` |
| `getTransactions_withFlaggedFilter_callsCorrectRepository` | `?flagged=true` routes to `findByFlagged(true, ...)` |
| `getTransactions_withAccountIdFilter_callsCorrectRepository` | `?accountId=ACC-005` routes to `findByAccountId(...)` |
| `getTransactions_withBothFilters_callsCorrectRepository` | Both params route to `findByFlaggedAndAccountId(...)` |
| `getTransactions_returnsEmptyPage_whenNoResults` | Empty page serialises correctly — no NPE on empty `content` |

#### `GET /api/transactions/{id}`

| Test | What it checks |
|---|---|
| `getTransaction_returns200WithFraudFlags_whenFound` | Found transaction returns `{transaction: {...}, fraudFlags: [...]}` |
| `getTransaction_returns200WithEmptyFlags_whenTransactionNotFlagged` | Clean transaction returns empty `fraudFlags` array |
| `getTransaction_returns404_whenNotFound` | `Optional.empty()` from repository produces HTTP 404 |

#### `GET /api/fraud-flags`

| Test | What it checks |
|---|---|
| `getFraudFlags_returnsAllFlags_whenNoFilter` | No `ruleName` param → `findAll()` returns all flags |
| `getFraudFlags_filtersbyRuleName_whenParamProvided` | `?ruleName=HIGH_AMOUNT` routes to `findByRuleName(...)` |
| `getFraudFlags_returnsEmptyList_whenNoneExist` | Empty list serialises to `[]`, not null |

#### `GET /api/stats`

| Test | What it checks |
|---|---|
| `getStats_returnsCorrectCounts` | Counts, percentage (25.0), and per-rule breakdown all correct |
| `getStats_returnZeroPercentage_whenNoTransactions` | `total=0` triggers the divide-by-zero guard and returns `0.0` |

---

## Running the Tests

```bash
# Run all tests
cd fraud-detection-service
mvn test

# Run a single test class
mvn test -Dtest=HighAmountRuleTest

# Run a single test method
mvn test -Dtest=HighAmountRuleTest#fires_whenAmountExceedsThreshold

# Run and skip tests (for building only)
mvn package -DskipTests
```

Expected output on a clean run:
```
Tests run: 70, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
