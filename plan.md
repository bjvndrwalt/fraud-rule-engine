# Fraud Rule Engine — Implementation Plan

## Context

Building a new event-driven fraud detection system alongside the existing Java Kafka order pipeline. The system consumes transaction events, evaluates a configurable set of fraud rules, persists all results to SQLite, and exposes a REST API for querying flagged transactions. This is a learning lab targeting production-grade patterns: idempotency, DLT handling, configurable rules, embedded persistence, and paginated REST.

All output files go to `/Users/BarendVanDerWalt1/Desktop/FRAUD RULE ENGINE/`.

---

## Architecture Decision: 2 Services (not 4)

The user proposed 4 parts. Parts 2–4 (classifier + DB + API) collapse into one service with no loss of structure:

| User's part | Lives in |
|---|---|
| Kafka transaction producer | `transaction-producer-service` |
| Kafka transaction consumer | `fraud-detection-service` — consumer layer |
| Fraud classifier / rules config | `fraud-detection-service` — rules package |
| SQLite data store | `fraud-detection-service` — embedded JPA/SQLite |
| REST API | `fraud-detection-service` — api package |

Splitting classifier/DB/API into separate services adds inter-service hops with no benefit at lab scale, and mirrors no real-world microservice boundary.

**Kafka topic:** `transaction.created` (key = `accountId` — routes same-account events to same partition for ordered frequency detection)

**Pipeline:**
```
transaction-producer-service
  → Kafka: transaction.created
    → fraud-detection-service (consume → classify → persist → serve)
      → REST API: localhost:8080/api/...  (NodePort 30085 in k8s)
```

---

## Service 1: `transaction-producer-service`

### Files to create (6)

**`pom.xml`** — Identical structure to `order-producer-service/pom.xml`. Parent: `spring-boot-starter-parent:3.4.0`, Java 17. Same 4 dependencies: `spring-boot-starter`, `spring-kafka`, `jackson-databind`, `jackson-datatype-jsr310`.

**`application.properties`** — One line only (programmatic config owns everything else):
```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:30092}
```

**`TransactionProducerApplication.java`** — `@SpringBootApplication` + `SpringApplication.run(...)`. Package `com.example.transactionproducer`.

**`config/KafkaProducerConfig.java`** — Direct copy of `order-producer-service/.../config/KafkaProducerConfig.java`, package renamed to `com.example.transactionproducer.config`. All settings unchanged (acks=all, idempotence=true, retries=MAX_VALUE, delivery.timeout=120s, linger=5ms, batch=32KB, lz4).

**`model/TransactionCreatedEvent.java`** — Plain POJO, package `com.example.transactionproducer.model`:
```
String transactionId   // UUID — unique per event
String accountId       // from fixed pool of 10 — also the Kafka message key
double amount          // ZAR
String merchantCategory // RETAIL, GROCERY, FUEL, RESTAURANT, TRAVEL, GAMBLING, CRYPTO, FOREX
String merchantId      // UUID
String channel         // ATM, POS, ONLINE
Instant transactionTime
```
No-arg constructor + all-args constructor + getters + setters + `toString()`.

**`producer/TransactionProducerRunner.java`** — Implements `CommandLineRunner`. Constructor-injected `KafkaTemplate<String, Object>` and `@Value("${TRANSACTION_TOPIC:transaction.created}") String transactionTopic`.

Static pool: `List.of("ACC-001" … "ACC-010")` — 10 fixed account IDs.

Weighted random generation per loop iteration:
- Merchant category: 0–39=RETAIL, 40–59=GROCERY, 60–74=FUEL, 75–84=RESTAURANT, 85–89=TRAVEL, 90–92=GAMBLING, 93–96=CRYPTO, 97–99=FOREX
- Channel: 0–59=POS, 60–89=ONLINE, 90–99=ATM
- Amount: 0–79 → R20–R5000 (normal), 80–89 → R10001–R100000 (high), 90–94 → round (1000/2000/5000/10000/15000), 95–99 → very round+high (20000/50000)
- `transactionTime`: 90% `Instant.now()`, 10% synthetic unusual hour (02:xx SAST)

**Burst simulation** (2% chance per iteration): pick one accountId and fire 6 events without sleep between them → triggers HIGH_FREQUENCY rule.

`kafkaTemplate.send(topic, accountId, event)` — key is `accountId`. Async `.whenComplete(...)` callback logs result. `Thread.sleep(1000)` between iterations.

---

## Service 2: `fraud-detection-service`

### Dependencies — `pom.xml`

Same 4 base dependencies, plus:
- `spring-boot-starter-web` — REST API (Tomcat embedded)
- `spring-boot-starter-data-jpa` — JPA + Hibernate 6.6.x
- `org.xerial:sqlite-jdbc:3.45.3.0`
- `org.hibernate.orm:hibernate-community-dialects:${hibernate.version}` — `SQLiteDialect`; `${hibernate.version}` is resolved by the Boot 3.4.0 BOM, staying in sync with Hibernate Core.

### `application.properties`

```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:30092}
spring.kafka.consumer.group-id=${CONSUMER_GROUP_ID:fraud-detection-group}
spring.kafka.consumer.auto-offset-reset=earliest

spring.datasource.url=jdbc:sqlite:${DB_PATH:./fraud_engine.db}
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false

# Serializes all DB writes through one connection — prevents SQLITE_BUSY with concurrency=3
spring.datasource.hikari.maximum-pool-size=1
spring.datasource.hikari.connection-timeout=30000

spring.jackson.serialization.write-dates-as-timestamps=false

fraud.rules.high-amount.enabled=true
fraud.rules.high-amount.threshold=10000.0
fraud.rules.unusual-hour.enabled=true
fraud.rules.unusual-hour.start-hour=0
fraud.rules.unusual-hour.end-hour=4
fraud.rules.high-risk-merchant.enabled=true
fraud.rules.high-risk-merchant.categories=GAMBLING,CRYPTO,FOREX
fraud.rules.round-amount.enabled=true
fraud.rules.round-amount.multiple=1000
fraud.rules.round-amount.min-amount=5000
fraud.rules.high-frequency.enabled=true
fraud.rules.high-frequency.max-count=5
fraud.rules.high-frequency.window-minutes=10
```

### Package structure

```
com.example.frauddetection
├── FraudDetectionApplication.java       (@SpringBootApplication + @EnableConfigurationProperties)
├── config/
│   ├── KafkaConsumerConfig.java
│   ├── KafkaDltProducerConfig.java
│   └── FraudRulesConfig.java            (@ConfigurationProperties)
├── model/
│   └── TransactionCreatedEvent.java     (same POJO as producer)
├── entity/
│   ├── Transaction.java                 (@Entity, table: transactions)
│   └── FraudFlag.java                   (@Entity, table: fraud_flags)
├── repository/
│   ├── TransactionRepository.java
│   └── FraudFlagRepository.java
├── rules/
│   ├── FraudRule.java                   (interface)
│   ├── FraudFlagResult.java             (immutable value object)
│   ├── HighAmountRule.java              (@Component @Order(1))
│   ├── UnusualHourRule.java             (@Component @Order(2))
│   ├── HighRiskMerchantRule.java        (@Component @Order(3))
│   ├── RoundAmountRule.java             (@Component @Order(4))
│   └── HighFrequencyRule.java           (@Component @Order(5))
├── service/
│   ├── FraudClassifierService.java
│   └── TransactionPersistenceService.java
├── consumer/
│   └── TransactionConsumer.java
└── api/
    └── TransactionController.java
```

---

### Layer-by-layer file specs

#### Entities

**`entity/Transaction.java`**
```
@Entity @Table(name="transactions")
String id (PK — transactionId UUID)
String accountId
double amount
String merchantCategory, merchantId, channel
Instant transactionTime, receivedAt
boolean flagged (default false)
```
No `@OneToMany fraudFlags` — causes `LazyInitializationException` during REST serialization. Fetch flags separately via repository.

**`entity/FraudFlag.java`**
```
@Entity @Table(name="fraud_flags")
Long id (@GeneratedValue IDENTITY — works with SQLite INTEGER PK)
@ManyToOne(LAZY) @JsonIgnore Transaction transaction  ← @JsonIgnore MANDATORY
String ruleName, severity (HIGH/MEDIUM/LOW), detail
Instant flaggedAt
```

#### Repositories

**`TransactionRepository`** extends `JpaRepository<Transaction, String>`:
```java
Page<Transaction> findByFlagged(boolean, Pageable)
Page<Transaction> findByAccountId(String, Pageable)
Page<Transaction> findByFlaggedAndAccountId(boolean, String, Pageable)
long countByFlagged(boolean)

@Query("SELECT COUNT(t) FROM Transaction t WHERE t.accountId = :accountId AND t.transactionTime > :since")
long countRecentTransactions(@Param("accountId") String, @Param("since") Instant)
```

**`FraudFlagRepository`** extends `JpaRepository<FraudFlag, Long>`:
```java
List<FraudFlag> findByRuleName(String)
List<FraudFlag> findByTransactionId(String)  // traverses transaction.id

@Query("SELECT f.ruleName as ruleName, COUNT(f) as count FROM FraudFlag f GROUP BY f.ruleName")
List<RuleCount> countGroupedByRuleName()

interface RuleCount { String getRuleName(); Long getCount(); }
```

#### Config: `FraudRulesConfig.java`

`@ConfigurationProperties(prefix = "fraud.rules")`. Five nested static config classes: `HighAmountConfig`, `UnusualHourConfig`, `HighRiskMerchantConfig`, `RoundAmountConfig`, `HighFrequencyConfig`. Each has `boolean enabled` + rule-specific fields with defaults. Initialize `List<String> categories` as `new ArrayList<>(List.of(...))` — not `List.of(...)` which is immutable and blocks Spring's property binder.

#### Rules

`FraudRule` interface: `List<FraudFlagResult> evaluate(Transaction tx)`

`FraudFlagResult`: immutable record-like class — `String ruleName, severity, detail`. Getters only.

| Rule class | Severity | Key logic |
|---|---|---|
| `HighAmountRule` | HIGH | `tx.getAmount() > threshold` |
| `UnusualHourRule` | MEDIUM | `tx.getTransactionTime().atZone("Africa/Johannesburg").getHour()` in `[startHour, endHour)` |
| `HighRiskMerchantRule` | HIGH | `categories.contains(tx.getMerchantCategory().toUpperCase())` |
| `RoundAmountRule` | LOW | `(long)amount % (long)multiple == 0 && amount >= minAmount` (cast to long, not float modulo) |
| `HighFrequencyRule` | HIGH | `countRecentTransactions(accountId, now-window) >= maxCount` — called after tx is persisted |

All return `Collections.emptyList()` when disabled or threshold not met.

#### `FraudClassifierService`

```java
@Service — constructor-injected List<FraudRule> rules (Spring auto-collects all @Component FraudRule beans)
List<FraudFlagResult> evaluate(Transaction tx) {
    return rules.stream().flatMap(r -> r.evaluate(tx).stream()).collect(toList());
}
```

#### `TransactionPersistenceService`

`@Service`, `@Transactional` on `processAndPersist(TransactionCreatedEvent event)`:
1. **Idempotency check**: `if (transactionRepository.existsById(event.getTransactionId())) return;`
2. Map event → `Transaction` entity, set `receivedAt = Instant.now()`, `flagged = false`
3. `transactionRepository.save(tx)` — persisted before rule evaluation so HIGH_FREQUENCY count includes it
4. `fraudClassifierService.evaluate(tx)` → results
5. If results not empty: set `tx.setFlagged(true)`, save each `FraudFlag`, re-save `tx`

#### Kafka Config

**`KafkaDltProducerConfig`** — copy `inventory-service/.../config/KafkaDltProducerConfig.java`, rename package. Produces `KafkaTemplate<String, Object> kafkaTemplate(...)` (the only KafkaTemplate bean in context).

**`KafkaConsumerConfig`** — copy `inventory-service/.../config/InventoryConsumerConfig.java` (the one WITH the `commitSync` override), adapt:
- `VALUE_DEFAULT_TYPE` = `"com.example.frauddetection.model.TransactionCreatedEvent"`
- `TRUSTED_PACKAGES` = `"com.example.frauddetection.model"`
- `USE_TYPE_INFO_HEADERS` = `false`
- All other settings unchanged (concurrency=3, MANUAL_IMMEDIATE, fetch tuning, FixedBackOff(1000ms, 3 retries))

#### `TransactionConsumer`

```java
@KafkaListener(topics = "${TRANSACTION_TOPIC:transaction.created}", groupId = "fraud-detection-group")
public void listen(TransactionCreatedEvent event, Acknowledgment ack) {
    if (event == null) { ack.acknowledge(); return; }
    persistenceService.processAndPersist(event);
    ack.acknowledge();  // only after DB transaction committed
}
```

#### `TransactionController`

```
GET /api/transactions                         Paginated list; query params: flagged, accountId, page, size
GET /api/transactions/{id}                    Single tx + its fraud flags → Map<String,Object>
GET /api/fraud-flags                          All flags; query param: ruleName
GET /api/stats                                totalTransactions, flaggedCount, flaggedPercentage, byRule map
```

Default sort: `transactionTime` descending. `Page<Transaction>` serializes correctly via Spring MVC's Jackson support (no extra config).

---

## Kubernetes Additions (append to `k8s/java-services.yaml`)

**transaction-producer Deployment** — env: `KAFKA_BOOTSTRAP_SERVERS`, `TRANSACTION_TOPIC=transaction.created`. No Service (outbound-only). Resources: 100m/128Mi req, 250m/256Mi lim.

**fraud-detection Deployment** — env: `KAFKA_BOOTSTRAP_SERVERS`, `TRANSACTION_TOPIC=transaction.created`. Exposes `containerPort: 8080`. Resources: 200m/256Mi req, 500m/512Mi lim (JPA + Tomcat need more than bare Kafka consumers).

**ClusterIP Service** — `fraud-detection-service:8080` for in-cluster access.

**NodePort Service** — `nodePort: 30085` → accessible at `localhost:30085/api/...` locally.

`DB_PATH` not set in k8s — defaults to `./fraud_engine.db` (ephemeral, acceptable for lab). For production add PVC and set `DB_PATH=/data/fraud_engine.db`.

---

## Implementation Order

| # | Action | Build check |
|---|---|---|
| 1 | Create `transaction-producer-service` tree | — |
| 2 | pom.xml, application.properties, Application class | `mvn validate` |
| 3 | `TransactionCreatedEvent.java` | — |
| 4 | Copy + adapt `KafkaProducerConfig.java` | `mvn compile` |
| 5 | `TransactionProducerRunner.java` | `mvn package -DskipTests` ✓ |
| 6 | Create `fraud-detection-service` tree | — |
| 7 | pom.xml | `mvn validate` |
| 8 | application.properties + `FraudDetectionApplication.java` | — |
| 9 | `Transaction.java` + `FraudFlag.java` entities | — |
| 10 | `TransactionRepository` + `FraudFlagRepository` | `mvn compile` — confirms JPA+SQLite wires |
| 11 | `FraudRulesConfig.java` | — |
| 12 | `FraudRule.java` + `FraudFlagResult.java` + 5 rule classes | — |
| 13 | `FraudClassifierService.java` | `mvn compile` |
| 14 | `TransactionPersistenceService.java` | — |
| 15 | `KafkaDltProducerConfig.java` + `KafkaConsumerConfig.java` | — |
| 16 | `TransactionConsumer.java` | `mvn compile` |
| 17 | `TransactionController.java` | `mvn package -DskipTests` ✓ |
| 18 | Append k8s Deployment + Services to `java-services.yaml` | — |

---

## Verification

### Local (Kafka on localhost:30092)

```bash
# 1. Start fraud-detection first — confirms DB schema creates on startup
cd fraud-detection-service && mvn spring-boot:run

# 2. Start producer
cd transaction-producer-service && mvn spring-boot:run

# 3. After ~2 minutes, check stats
curl -s localhost:8080/api/stats | jq .
# Expected: all 5 rule names present in byRule map

# 4. Inspect a flagged transaction
TX=$(curl -s "localhost:8080/api/transactions?flagged=true" | jq -r '.content[0].id')
curl -s "localhost:8080/api/transactions/$TX" | jq '{amount: .transaction.amount, rules: [.fraudFlags[].ruleName]}'

# 5. DLT test — send malformed JSON via Kafka UI or console producer to transaction.created
# Confirm: 3 retries in logs, then routes to transaction.created.DLT, consumer continues

# 6. Idempotency test — resend an existing transactionId via Kafka UI
# Confirm: "Duplicate event skipped: {id}" log line, no duplicate DB row
```

### k8s

```bash
kubectl apply -f k8s/java-services.yaml
kubectl get pods -w   # wait for Running
kubectl port-forward svc/fraud-detection-nodeport 8080:8080
curl localhost:8080/api/stats
```

---

## Known Edge Cases (already mitigated in this plan)

| Risk | Mitigation |
|---|---|
| `SQLITE_BUSY` under 3 concurrent writer threads | `hikari.maximum-pool-size=1` |
| `LazyInitializationException` in REST responses | No `@OneToMany` on `Transaction`; `@JsonIgnore` on `FraudFlag.transaction` |
| Duplicate Kafka delivery on consumer restart | `existsById()` guard at top of `processAndPersist` |
| HIGH_FREQUENCY race across concurrent threads | accountId = partition key → same-account events always on same thread |
| Float modulo precision for ROUND_AMOUNT | Cast to `long` before `%` operator |
| Immutable `List` in `@ConfigurationProperties` | Initialize `categories` as `new ArrayList<>(List.of(...))` |
| Unusual-hour rule timezone | Hardcoded `ZoneId.of("Africa/Johannesburg")` (SAST UTC+2) |
