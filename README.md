# Fraud Rule Engine

A real-time fraud detection system built with Spring Boot and Apache Kafka. Transactions are streamed from a producer service, evaluated against a configurable set of fraud rules, and persisted with their fraud flags for querying via a REST API.

---

## Architecture

```
┌─────────────────────────────┐        ┌──────────────────────────────────────┐
│  transaction-producer       │        │  fraud-detection                     │
│                             │        │                                      │
│  Generates random           │        │  Consumes events → evaluates 5       │
│  TransactionCreatedEvents   │──────▶ │  fraud rules → persists to SQLite    │
│  at ~1 tx/sec               │        │  → serves REST API on :8080          │
│  (2% burst: 6 rapid tx)     │        │                                      │
└─────────────────────────────┘        └──────────────────────────────────────┘
              │                                         │
              └──────────────┬──────────────────────────┘
                             ▼
                   Kafka topic: transaction.created
                   DLT:         transaction.created.DLT
```

Both services connect to a single Kafka broker. Failed messages (after 3 retries) are automatically routed to the Dead Letter Topic and logged by a dedicated DLT consumer.

---

## Services

### transaction-producer-service

- Publishes `TransactionCreatedEvent` messages to the `transaction.created` Kafka topic
- Runs a continuous loop — one transaction per second with a 2% chance of a burst of 6 rapid transactions (used to trigger the `HIGH_FREQUENCY` rule)
- No HTTP server — Kafka producer only

### fraud-detection-service

- Consumes `transaction.created` with 3 parallel Kafka listeners
- Evaluates each transaction against 5 configurable fraud rules
- Persists transactions and their fraud flags to SQLite
- Exposes a REST API on port `8080`
- Routes messages that fail after 3 retries to `transaction.created.DLT`

---

## Fraud Rules

All rules are enabled and configurable via `application.properties` or environment variables.

| Rule | Default Trigger | Severity |
|------|----------------|----------|
| `HIGH_AMOUNT` | Amount > R10,000 | HIGH |
| `UNUSUAL_HOUR` | Transaction between 00:00–04:00 SAST | MEDIUM |
| `HIGH_RISK_MERCHANT` | Merchant category in GAMBLING, CRYPTO, FOREX | HIGH |
| `ROUND_AMOUNT` | Amount is a multiple of R1,000 and ≥ R5,000 | LOW |
| `HIGH_FREQUENCY` | ≥ 5 transactions for the same account in 10 minutes | HIGH |

---

## Getting Started

### Prerequisites

- Docker Desktop

### Run locally

```bash
git clone https://github.com/bjvndrwalt/fraud-rule-engine.git
cd fraud-rule-engine
docker compose up --build
```

This starts four containers:

| Container | Description | Port |
|-----------|-------------|------|
| `kafka` | Bitnami Kafka 3.7 (KRaft, no Zookeeper) | internal |
| `transaction-producer` | Publishes transactions to Kafka | — |
| `fraud-detection` | Fraud rules engine + REST API | `8080` |
| `kafka-ui` | Kafka UI dashboard | `8090` |

Allow ~30 seconds for Kafka to become healthy and for both Spring Boot services to start.

### Verify it's running

```bash
# Fraud stats
curl http://localhost:8080/api/stats

# Recent transactions (paginated)
curl http://localhost:8080/api/transactions

# Flagged transactions only
curl "http://localhost:8080/api/transactions?flagged=true"

# Kafka UI
open http://localhost:8090
```

---

## REST API

All endpoints are under `/api`.

### `GET /api/transactions`

Returns a paginated list of transactions.

| Query param | Type | Description |
|-------------|------|-------------|
| `flagged` | boolean | Filter to flagged transactions only |
| `accountId` | string | Filter by account ID |
| `page` | int | Page number (default 0) |
| `size` | int | Page size (default 20) |

### `GET /api/transactions/{id}`

Returns a single transaction with all its fraud flags.

### `GET /api/fraud-flags`

Returns all fraud flags.

| Query param | Type | Description |
|-------------|------|-------------|
| `ruleName` | string | Filter by rule name (e.g. `HIGH_AMOUNT`) |

### `GET /api/stats`

Returns a summary of rule firing counts and percentages.

---

## Configuration

### Environment variables

| Variable | Default | Service |
|----------|---------|---------|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:30092` | both |
| `TRANSACTION_TOPIC` | `transaction.created` | both |
| `CONSUMER_GROUP_ID` | `fraud-detection-group` | fraud-detection |
| `DB_PATH` | `./fraud_engine.db` | fraud-detection |
| `DLT_TOPIC` | `transaction.created.DLT` | fraud-detection |

### Fraud rule tuning

Rules can be adjusted in `fraud-detection-service/src/main/resources/application.properties`:

```properties
fraud.rules.high-amount.threshold=10000.0
fraud.rules.unusual-hour.start-hour=0
fraud.rules.unusual-hour.end-hour=4
fraud.rules.high-risk-merchant.categories=GAMBLING,CRYPTO,FOREX
fraud.rules.round-amount.multiple=1000
fraud.rules.round-amount.min-amount=5000
fraud.rules.high-frequency.max-count=5
fraud.rules.high-frequency.window-minutes=10
```

Any rule can be disabled individually:

```properties
fraud.rules.round-amount.enabled=false
```

---

## Project Structure

```
fraud-rule-engine/
├── transaction-producer-service/   # Kafka producer microservice
│   ├── src/
│   └── Dockerfile
├── fraud-detection-service/        # Fraud rules engine + REST API
│   ├── src/
│   │   ├── main/java/com/example/frauddetection/
│   │   │   ├── config/             # Kafka consumer, producer, DLT config
│   │   │   ├── consumer/           # TransactionConsumer, DltConsumer
│   │   │   ├── rules/              # One class per fraud rule
│   │   │   ├── service/            # FraudClassifierService, TransactionPersistenceService
│   │   │   ├── api/                # TransactionController (REST endpoints)
│   │   │   ├── entity/             # Transaction, FraudFlag (JPA entities)
│   │   │   └── model/              # TransactionCreatedEvent (Kafka DTO)
│   │   └── test/
│   └── Dockerfile
├── k8s/
│   └── java-services.yaml          # Kubernetes Deployments, Services, PVC
├── docker-compose.yml              # Full local stack
└── README.md
```

---

## Kubernetes Deployment

Image names in `k8s/java-services.yaml` use the prefix `fraud-rule-engine/` — update this to match your registry before applying.

```bash
# Build and push images
docker build -t <your-registry>/fraud-rule-engine/transaction-producer:latest ./transaction-producer-service
docker build -t <your-registry>/fraud-rule-engine/fraud-detection:latest ./fraud-detection-service
docker push <your-registry>/fraud-rule-engine/transaction-producer:latest
docker push <your-registry>/fraud-rule-engine/fraud-detection:latest

# Deploy
kubectl apply -f k8s/java-services.yaml

# Access the API (NodePort 30085)
curl http://<node-ip>:30085/api/stats
```

The manifest includes a `PersistentVolumeClaim` (`fraud-db-pvc`, 1 Gi) that mounts at `/data` in the fraud-detection pod, ensuring SQLite data survives pod restarts.

---

## Dead Letter Topic

Messages that fail processing after 3 retry attempts (1-second fixed backoff) are automatically routed to `transaction.created.DLT` by Spring Kafka's `DeadLetterPublishingRecoverer`. A dedicated `DltConsumer` reads from this topic and logs the full diagnostic context — original topic, offset, exception class, exception message, and raw payload — at `ERROR` level.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.4 |
| Messaging | Apache Kafka (Spring Kafka) |
| Persistence | SQLite + Spring Data JPA + Hibernate |
| Serialization | Jackson |
| Build | Maven |
| Container | Docker (multi-stage builds) |
| Orchestration | Kubernetes |