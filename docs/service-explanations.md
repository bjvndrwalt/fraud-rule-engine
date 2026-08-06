# Constructor and Service Layer Explanations


## How Rules Are Constructed and Added to FraudClassifierService

There are two separate things happening here — how each rule gets its **configuration**, and how the rules get **registered** with `FraudClassifierService`. They work independently but connect at startup.

---

### Part 1 — How rules get their configuration (`FraudRulesConfig`)

**Line 8 — `@ConfigurationProperties(prefix = "fraud.rules")`**
This annotation tells Spring: read `application.properties`, find every key that starts with `fraud.rules`, and map the values onto the fields of this class automatically. So:
```properties
fraud.rules.high-amount.threshold=10000.0
```
…maps to `FraudRulesConfig → highAmount → threshold = 10000.0`.

**Lines 11–15 — Five nested config objects**
Each rule has its own inner config class (`HighAmountConfig`, `UnusualHourConfig`, etc.). They are initialised with default values here so the service still works even if `application.properties` is missing those keys entirely.

**Lines 32–41 — Example: `HighAmountConfig`**
A simple data holder with two fields: `enabled` (a kill switch to turn the rule off without deleting it) and `threshold` (the amount in rands above which a transaction is suspicious). Spring populates these from `application.properties` using the setters.

**Line 61 — `ArrayList` not `List.of`**
```java
private List<String> categories = new ArrayList<>(List.of("GAMBLING", "CRYPTO", "FOREX"));
```
This is important for `HighRiskMerchantConfig`. Spring's property binder works by calling `.add()` on the list — that only works on a mutable `ArrayList`. If you used `List.of(...)` (which is immutable), Spring would throw an error when trying to populate it from `application.properties`.

---

### Part 2 — How a rule uses its config (`HighAmountRule` as the example)

**Lines 11–12 — `@Component` + `@Order(1)`**
- `@Component` registers this class as a Spring bean — it becomes part of the application context at startup
- `@Order(1)` sets its position in the list when Spring injects all `FraudRule` beans into `FraudClassifierService`. Rules run in order 1 → 5

**Lines 15–19 — Constructor**
```java
private final FraudRulesConfig.HighAmountConfig config;

public HighAmountRule(FraudRulesConfig fraudRulesConfig) {
    this.config = fraudRulesConfig.getHighAmount();
}
```
Spring injects the full `FraudRulesConfig` object. The constructor immediately extracts only the `HighAmountConfig` slice it cares about and stores that. The rule never sees the other rules' config — clean separation.

**Lines 22–31 — `evaluate()`**
```java
if (!config.isEnabled() || tx.getAmount() <= config.getThreshold()) {
    return Collections.emptyList();
}
return List.of(new FraudFlagResult("HIGH_AMOUNT", "HIGH", ...));
```
Two exit paths:
- If the rule is disabled or the amount doesn't exceed the threshold → return an empty list (no flag)
- Otherwise → return a list containing one `FraudFlagResult` with the rule name, severity, and a detail message

All 5 rules follow this exact same pattern — check if disabled, check if condition met, return empty or return a result.

---

### How it all connects at startup

```
application.properties
        ↓
FraudRulesConfig (@ConfigurationProperties — Spring reads + maps values)
        ↓
HighAmountRule, UnusualHourRule, etc. (each @Component pulls its slice of config)
        ↓
FraudClassifierService (Spring injects all @Component FraudRule beans as List<FraudRule>)
        ↓
evaluate() is called per transaction — each rule checks its own thresholds
```

The practical benefit: to change the high-amount threshold from R10,000 to R50,000, you only edit one line in `application.properties` and restart. No code change needed.

---

## TransactionPersistenceService

```java
@Service
public class TransactionPersistenceService {
```

**Lines 17–18 — `@Service`**
Tells Spring this is a service bean. Spring creates one instance and makes it available for injection anywhere else that needs it (in this case, `TransactionConsumer`).

---

**Line 20 — Logger**
Creates a logger tied to this class. `log.info(...)` calls later write to the console/log file with the class name as the source.

---

**Lines 22–32 — Constructor injection**
The three dependencies are injected via the constructor (not `@Autowired` on fields — constructor injection is preferred because it makes dependencies explicit and testable). Spring wires these automatically at startup.

Notice that the third parameter is typed as `FraudClassifier` (the interface), not `FraudClassifierService` (the concrete class). This is intentional — see the **FraudClassifier Interface** section below for why.

---

**Line 34 — `@Transactional`**
Everything inside `processAndPersist` runs inside a single database transaction. If anything throws an exception after the first `save`, all database writes in this method are rolled back automatically — no partial records left in the DB.

---

**Lines 36–39 — Idempotency guard**
```java
if (transactionRepository.existsById(event.getTransactionId())) {
    log.info("Duplicate event skipped: {}", event.getTransactionId());
    return;
}
```
Kafka guarantees *at-least-once* delivery — the same message can arrive twice (e.g. after a consumer restart). This check prevents saving the same transaction twice. If the ID already exists in the DB, it logs a message and exits immediately without touching anything.

---

**Lines 41–50 — Map event → entity**
The Kafka message arrives as a `TransactionCreatedEvent` (a plain data object). These lines translate it into a `Transaction` JPA entity (the thing that actually maps to the database table). Two fields are set here that didn't exist in the event:
- `receivedAt = Instant.now()` — stamps when *this service* received it, separate from when the transaction occurred
- `flagged = false` — default state; changed to `true` later only if a rule fires

---

**Line 53 — First save (before rules)**
```java
transactionRepository.save(tx);
```
The transaction is written to the database *before* the fraud rules run. This is intentional: the `HighFrequencyRule` counts recent transactions for this account using a database query. If we saved *after* the rules, the current transaction wouldn't be in the count yet, and burst detection would be off by one.

---

**Line 55 — Run all fraud rules**
```java
List<FraudFlagResult> results = fraudClassifierService.evaluate(tx);
```
Passes the transaction to `FraudClassifierService`, which loops through all 5 rules in order (`@Order(1)` through `@Order(5)`) and collects every rule that fired. Returns an empty list if nothing matched.

---

**Lines 57–72 — Persist results if anything was flagged**
```java
if (!results.isEmpty()) {
```
Only enters this block if at least one rule fired.

- **Lines 58–59**: Marks the transaction as flagged and saves it again to update the `is_flagged` column in the DB.
- **Lines 61–68**: For each rule result, creates a `FraudFlag` row — stores which rule fired, the severity (HIGH/MEDIUM/LOW), and the human-readable detail message (e.g. `"Amount R15000.00 exceeds threshold R10000.00"`). Each flag is saved to the `fraud_flags` table and linked to the transaction via the `transaction_id` foreign key.
- **Lines 69–70**: Logs a line to the console for each flag so you can see it in real time when the service is running.

---

**The overall flow in plain English**

> A Kafka message arrives → check it's not a duplicate → convert it to a DB record → save it → run all fraud rules against it → if any rules fired, mark it as flagged, save a fraud_flag row for each rule that matched, and log it.

---

## FraudClassifierService

```java
@Service
public class FraudClassifierService implements FraudClassifier {

    private final List<FraudRule> rules;

    public FraudClassifierService(List<FraudRule> rules) {
        this.rules = rules;
    }

    public List<FraudFlagResult> evaluate(Transaction tx) {
        return rules.stream()
                .flatMap(rule -> rule.evaluate(tx).stream())
                .collect(Collectors.toList());
    }
}
```

**Line 11 — `@Service`**
Same as `TransactionPersistenceService` — Spring creates one instance and manages it as a bean.

---

**Line 14 — `private final List<FraudRule> rules`**
Rather than referencing each rule class individually, the service holds a *list of anything that implements the `FraudRule` interface*. It doesn't know or care which rules exist — it just knows they all have an `evaluate()` method.

---

**Lines 16–18 — Constructor injection of the rule list**
```java
public FraudClassifierService(List<FraudRule> rules) {
    this.rules = rules;
}
```
Spring sees that the constructor wants a `List<FraudRule>`. It automatically finds every bean in the application context that implements `FraudRule` — that's the 5 classes marked `@Component` (`HighAmountRule`, `UnusualHourRule`, `HighRiskMerchantRule`, `RoundAmountRule`, `HighFrequencyRule`) — and injects them as a list. Because each rule also has `@Order(1)` through `@Order(5)`, Spring populates the list in that order. You never manually wire up which rules exist — adding a new `@Component` rule class in the future is enough for it to automatically appear here.

---

**Lines 20–24 — `evaluate()`**
```java
return rules.stream()
        .flatMap(rule -> rule.evaluate(tx).stream())
        .collect(Collectors.toList());
```
Three steps happening on this single stream:

1. **`.stream()`** — turns the list of 5 rules into a stream so we can process them one by one
2. **`.flatMap(rule -> rule.evaluate(tx).stream())`** — for each rule, calls `evaluate(tx)` which returns a `List<FraudFlagResult>` (either empty or containing one result). `flatMap` flattens those 5 separate lists into a single stream of results. If rules 1, 3, and 5 each return one result and rules 2 and 4 return empty lists, `flatMap` produces a stream of 3 results total
3. **`.collect(Collectors.toList())`** — gathers everything back into a final `List<FraudFlagResult>` which gets returned to `TransactionPersistenceService`

---

**Why it's designed this way**

The pattern is called **Strategy + Open/Closed principle**. `FraudClassifierService` is closed for modification but open for extension — to add a new fraud rule you just create a new `@Component` class that implements `FraudRule`, give it an `@Order`, and it automatically gets picked up. You never touch this file. The classifier's only job is orchestration — it knows nothing about what the rules actually do.

**`implements FraudClassifier`**
`FraudClassifierService` declares that it implements the `FraudClassifier` interface. This means Spring can inject it anywhere the interface type is requested — which is exactly what `TransactionPersistenceService` does. See the **FraudClassifier Interface** section below.

---

## FraudClassifier Interface

```java
public interface FraudClassifier {
    List<FraudFlagResult> evaluate(Transaction tx);
}
```

**Defined in:** `service/FraudClassifier.java`

A single-method contract: anything that implements `FraudClassifier` must provide an `evaluate(Transaction tx)` method that returns a list of fraud flag results.

This looks almost identical to `FraudRule` — and intentionally so — but it operates at a different level:

| Interface | Implemented by | Used by | Scope |
|---|---|---|---|
| `FraudRule` | The 5 individual rule classes | `FraudClassifierService` | One rule at a time |
| `FraudClassifier` | `FraudClassifierService` | `TransactionPersistenceService` | All rules as one unit |

`FraudClassifier` is the boundary between the **service layer** (persistence + orchestration) and the **rules layer** (individual fraud logic). `TransactionPersistenceService` declares its dependency as:

```java
private final FraudClassifier fraudClassifierService;
```

It doesn't know — and doesn't need to know — that `FraudClassifierService` internally loops over 5 rules. It just calls `evaluate(tx)` and gets back a list of results.

---

**Why a separate interface rather than just depending on `FraudClassifierService` directly?**

Two reasons:

**1. Testability on Java 21+**

Mockito can mock objects in two ways:
- **Interface** → uses JDK's built-in `Proxy` mechanism. No bytecode changes needed. Works on any JVM.
- **Concrete class** → uses a bytecode instrumentation agent to subclass the target at runtime. Requires dynamic agent loading.

Java 21 introduced restrictions on dynamic agent loading; Java 26 enforces them strictly. When the test suite was first written with `@Mock FraudClassifierService` (a concrete class), every test in `TransactionPersistenceServiceTest` threw:
```
Mockito cannot mock this class: class FraudClassifierService
Underlying exception: Could not modify all classes [FraudClassifierService, java.lang.Object]
```
Changing the field to `@Mock FraudClassifier` (an interface) fixed all 7 failing tests instantly — Mockito creates a JDK proxy with no bytecode manipulation required.

**2. Depend on abstractions, not implementations**

This is the **Dependency Inversion Principle** (the D in SOLID). `TransactionPersistenceService` should not be coupled to the specific class that evaluates rules — only to the contract that says "given a transaction, return flag results." If you ever replaced `FraudClassifierService` with a different implementation (say, an ML-based scorer), `TransactionPersistenceService` would need zero changes.

---

**How all three layers connect**

```
FraudRule (interface)
    ↑ implemented by
HighAmountRule, UnusualHourRule, HighRiskMerchantRule, RoundAmountRule, HighFrequencyRule
    ↓ injected into
FraudClassifierService  ──implements──▶  FraudClassifier (interface)
                                                ↓ injected into
                                        TransactionPersistenceService
```

Spring resolves the injection automatically: `TransactionPersistenceService` asks for a `FraudClassifier` bean; Spring finds `FraudClassifierService` because it `implements FraudClassifier`, and injects it.

---

## TransactionController

```java
@RestController
@RequestMapping("/api")
public class TransactionController {
```

**Lines 23–24 — `@RestController` + `@RequestMapping("/api")`**
- `@RestController` registers this class as a Spring-managed HTTP handler. It combines `@Controller` and `@ResponseBody`, meaning every method's return value is automatically serialised to JSON — no extra annotation needed per method.
- `@RequestMapping("/api")` sets the URL prefix for the whole class. Every endpoint below is relative to this, so `@GetMapping("/transactions")` becomes `GET /api/transactions`.

---

**Lines 27–34 — Fields and constructor injection**
```java
private final TransactionRepository transactionRepository;
private final FraudFlagRepository fraudFlagRepository;

public TransactionController(TransactionRepository transactionRepository,
                              FraudFlagRepository fraudFlagRepository) {
    this.transactionRepository = transactionRepository;
    this.fraudFlagRepository = fraudFlagRepository;
}
```
Two `final` fields — `final` means they can only be assigned once (in the constructor) and never reassigned. Spring sees there is only one constructor and automatically injects the two repository beans into it. No `@Autowired` needed. This is constructor injection — preferred in modern Spring because dependencies are explicit and the class is easy to unit-test (you can pass mock repositories directly). The controller never writes to the database — it only reads via these two repositories.

---

### `GET /api/transactions` — Lines 36–53

```java
@GetMapping("/transactions")
public Page<Transaction> getTransactions(
        @RequestParam Optional<Boolean> flagged,
        @RequestParam Optional<String> accountId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
) {
```
Handles four query parameters:
- `flagged` and `accountId` are `Optional<>` — if the caller omits them, Spring gives `Optional.empty()` rather than `null`. This avoids null-checks and makes the "was it provided?" question explicit.
- `page` defaults to `0` and `size` defaults to `20`. Spring auto-converts the string `"0"` / `"20"` to `int`. So `GET /api/transactions` returns the first 20 records with no filters.

```java
Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "transactionTime"));
```
Builds the pagination instruction: *"give me `size` records starting at offset `page × size`, sorted by `transactionTime` newest-first."* This single object is passed directly to the repository — Spring Data translates it into `LIMIT / OFFSET / ORDER BY` SQL automatically.

```java
if (flagged.isPresent() && accountId.isPresent()) {
    return transactionRepository.findByFlaggedAndAccountId(flagged.get(), accountId.get(), pageable);
} else if (flagged.isPresent()) {
    return transactionRepository.findByFlagged(flagged.get(), pageable);
} else if (accountId.isPresent()) {
    return transactionRepository.findByAccountId(accountId.get(), pageable);
}
return transactionRepository.findAll(pageable);
```
A decision tree over the four combinations of optional params. `.isPresent()` checks whether the caller supplied the value; `.get()` unwraps it. Spring Data derives the SQL `WHERE` clause from the method name — `findByFlaggedAndAccountId` becomes `WHERE is_flagged = ? AND account_id = ?`. The return type `Page<Transaction>` includes the items *plus* metadata (total record count, total pages, current page number) — useful for pagination controls in a UI.

---

### `GET /api/transactions/{id}` — Lines 55–66

```java
@GetMapping("/transactions/{id}")
public ResponseEntity<Map<String, Object>> getTransaction(@PathVariable String id) {
```
`{id}` is a URL template variable — e.g. `GET /api/transactions/TXN-00142`. `@PathVariable` binds whatever appears in that position to the `id` parameter. The return type is `ResponseEntity<...>` rather than the object directly, because this endpoint needs to control the HTTP status code — either `200 OK` with a body, or `404 Not Found` with no body.

```java
return transactionRepository.findById(id)
        .map(tx -> {
            List<FraudFlag> flags = fraudFlagRepository.findByTransactionId(id);
            Map<String, Object> response = new HashMap<>();
            response.put("transaction", tx);
            response.put("fraudFlags", flags);
            return ResponseEntity.ok(response);
        })
        .orElse(ResponseEntity.notFound().build());
```
`findById(id)` returns `Optional<Transaction>`. `.map()` on an `Optional` runs the lambda *only if a value is present* — if the transaction exists, it fetches the matching fraud flags, builds a two-key `HashMap`, and returns it wrapped in a `200 OK`. Jackson serialises the map to JSON:
```json
{
  "transaction": { ... },
  "fraudFlags": [ ... ]
}
```
`.orElse(...)` is the fallback if the `Optional` was empty — returns `404 Not Found` with an empty body. `.build()` is needed because `.notFound()` returns a builder, not a `ResponseEntity` directly.

---

### `GET /api/fraud-flags` — Lines 68–72

```java
@GetMapping("/fraud-flags")
public List<FraudFlag> getFraudFlags(@RequestParam Optional<String> ruleName) {
    return ruleName.map(fraudFlagRepository::findByRuleName)
            .orElseGet(fraudFlagRepository::findAll);
}
```
Returns all fraud flags, optionally filtered by rule name (e.g. `?ruleName=HIGH_AMOUNT`).

The one-liner uses two `Optional` methods chained together:
- `.map(fn)` — if `ruleName` is present, calls `findByRuleName(ruleName)` and wraps the result in an `Optional`
- `.orElseGet(fn)` — if still empty (no filter provided), calls `findAll()` as the fallback

`::` is a **method reference** — shorthand for writing the full lambda `name -> fraudFlagRepository.findByRuleName(name)`. The result is returned directly; `@RestController` serialises it to a JSON array automatically.

---

### `GET /api/stats` — Lines 74–90

```java
@GetMapping("/stats")
public Map<String, Object> getStats() {
    long total = transactionRepository.count();
    long flagged = transactionRepository.countByFlagged(true);
```
Two cheap aggregate SQL queries run immediately: `SELECT COUNT(*) FROM transactions` and `SELECT COUNT(*) FROM transactions WHERE is_flagged = true`. `count()` is inherited from `JpaRepository`; `countByFlagged` is a derived query method Spring Data generates from the method name.

```java
    Map<String, Long> byRule = new HashMap<>();
    for (FraudFlagRepository.RuleCount rc : fraudFlagRepository.countGroupedByRuleName()) {
        byRule.put(rc.getRuleName(), rc.getCount());
    }
```
`countGroupedByRuleName()` runs a JPQL `GROUP BY rule_name` query and returns a list of `RuleCount` projections — a Spring Data interface with two getters: `getRuleName()` and `getCount()`. The loop flattens that list into a plain `Map<String, Long>`, which serialises to clean JSON:
```json
{ "HIGH_AMOUNT": 12, "UNUSUAL_HOUR": 4, "HIGH_FREQUENCY": 2 }
```

```java
    stats.put("flaggedPercentage", total == 0 ? 0.0 : Math.round((flagged * 100.0 / total) * 10.0) / 10.0);
```
The ternary `total == 0 ? 0.0 : ...` is a divide-by-zero guard — prevents a crash on an empty database. The arithmetic trick `Math.round(x * 10.0) / 10.0` rounds to **one decimal place**: multiply by 10, round to the nearest integer, divide back by 10. So `14.666...` becomes `14.7`.

```java
    return stats;
}
```
Returns the map directly — no `ResponseEntity` needed here because this endpoint always succeeds with a `200 OK`. `@RestController` handles serialisation.

---

**The four endpoints at a glance**

| Endpoint | Returns | Notable behaviour |
|---|---|---|
| `GET /api/transactions` | `Page<Transaction>` | Optional `?flagged`, `?accountId`, `?page`, `?size` filters |
| `GET /api/transactions/{id}` | `{transaction, fraudFlags}` | `404` if not found |
| `GET /api/fraud-flags` | `List<FraudFlag>` | Optional `?ruleName` filter |
| `GET /api/stats` | `{total, flaggedCount, flaggedPercentage, byRule}` | Division-by-zero safe |

---

## FraudRule Interface

```java
public interface FraudRule {
    List<FraudFlagResult> evaluate(Transaction tx);
}
```

**Defined in:** `rules/FraudRule.java`

Just one line of contract: any class that implements `FraudRule` must provide an `evaluate(Transaction tx)` method that returns a list of results.

The 5 rule classes (`HighAmountRule`, `UnusualHourRule`, `HighRiskMerchantRule`, `RoundAmountRule`, `HighFrequencyRule`) all declare `implements FraudRule` and provide their own version of that method. That's what allows `FraudClassifierService` to call `rule.evaluate(tx)` on each one without knowing what any of them actually do — they all honour the same contract.
