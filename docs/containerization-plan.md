# Containerize the Fraud Rule Engine

## Context

The fraud rule engine consists of two Spring Boot microservices (`transaction-producer-service` and `fraud-detection-service`) that communicate via Kafka. The project has an existing Kubernetes manifest (`k8s/java-services.yaml`) with placeholder image names, but no Dockerfiles, no docker-compose, and no `.dockerignore` files exist yet. Containerization is needed to:
- Enable reproducible local development (replacing the manual `localhost:30092` assumption)
- Support the existing Kubernetes deployment path

---

## Files to Create / Modify

### 1. `transaction-producer-service/Dockerfile` _(new)_
Multi-stage build — Maven build + JRE runtime:
```dockerfile
# Stage 1 – build
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2 – runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/transaction-producer-service-*.jar app.jar
ENV KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    TRANSACTION_TOPIC=transaction.created
ENTRYPOINT ["java", "-jar", "app.jar"]
```
No `EXPOSE` — this service has no HTTP server.

---

### 2. `transaction-producer-service/.dockerignore` _(new)_
```
target/
*.db
.DS_Store
.git
```

---

### 3. `fraud-detection-service/Dockerfile` _(new)_
Same multi-stage pattern, exposes port 8080, DB path points to a mountable `/data` directory:
```dockerfile
# Stage 1 – build
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2 – runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/fraud-detection-service-*.jar app.jar
ENV KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    TRANSACTION_TOPIC=transaction.created \
    CONSUMER_GROUP_ID=fraud-detection-group \
    DB_PATH=/data/fraud_engine.db
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

### 4. `fraud-detection-service/.dockerignore` _(new)_
```
target/
*.db
.DS_Store
.git
```

---

### 5. `docker-compose.yml` (project root) _(new)_
Wires Kafka (KRaft mode, no Zookeeper), both services, and Kafka UI for local dev:
```yaml
services:
  kafka:
    image: bitnami/kafka:3.7
    healthcheck:
      test: ["CMD", "kafka-topics.sh", "--bootstrap-server", "localhost:9092", "--list"]
      interval: 10s
      timeout: 5s
      retries: 6
    environment:
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_PROCESS_ROLES: broker,controller
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: "true"

  transaction-producer:
    build: ./transaction-producer-service
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      kafka:
        condition: service_healthy
    restart: on-failure

  fraud-detection:
    build: ./fraud-detection-service
    ports:
      - "8080:8080"
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      DB_PATH: /data/fraud_engine.db
    volumes:
      - fraud-db:/data
    depends_on:
      kafka:
        condition: service_healthy
    restart: on-failure

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports:
      - "8090:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
    depends_on:
      - kafka

volumes:
  fraud-db:
```

---

### 6. `k8s/java-services.yaml` _(update)_
Three changes to the existing file:
- **Replace placeholder image names** with `fraud-rule-engine/transaction-producer:latest` and `fraud-rule-engine/fraud-detection:latest` (update the registry prefix to match your environment)
- **Add `DB_PATH` env var** to the `java-fraud-detection` Deployment: `value: /data/fraud_engine.db`
- **Add PersistentVolumeClaim + volumeMount** to the `java-fraud-detection` Deployment so SQLite data survives pod restarts:
  ```yaml
  # New PVC resource
  apiVersion: v1
  kind: PersistentVolumeClaim
  metadata:
    name: fraud-db-pvc
  spec:
    accessModes: [ReadWriteOnce]
    resources:
      requests:
        storage: 1Gi
  ```
  And in the Deployment's container spec:
  ```yaml
  volumeMounts:
    - name: fraud-db
      mountPath: /data
  volumes:
    - name: fraud-db
      persistentVolumeClaim:
        claimName: fraud-db-pvc
  ```

---

## Verification

1. **Build + start locally:**
   ```bash
   cd "FRAUD RULE ENGINE"
   docker compose up --build
   ```
2. **Check the REST API (give ~30s for startup):**
   ```bash
   curl http://localhost:8080/api/stats
   curl http://localhost:8080/api/transactions
   ```
3. **Check Kafka UI:** open `http://localhost:8090` — should see the `transaction.created` topic with growing offsets.
4. **Verify SQLite volume persists:** `docker compose down` then `docker compose up` — `/api/stats` should retain prior counts.
5. **K8s smoke test (if cluster available):**
   ```bash
   kubectl apply -f k8s/java-services.yaml
   kubectl get pods
   kubectl port-forward svc/fraud-detection-service 8080:8080
   curl http://localhost:8080/api/stats
   ```