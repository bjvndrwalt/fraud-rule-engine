# Docker Containerization — Fraud Rule Engine

## What is Docker Containerization?

A **container** is a lightweight, self-contained unit that packages an application together with everything it needs to run: the JRE, configuration, environment variables, and startup command. Containers run identically on any machine that has Docker installed — your laptop, a CI server, or a Kubernetes cluster — eliminating "it works on my machine" problems.

Think of it like a shipping container: the contents are sealed and standardised, so it doesn't matter whether the ship is sailing from Cape Town or Rotterdam — the container behaves the same.

---

## How This Project is Containerized

The fraud rule engine has **two microservices** that each get their own container image, plus a **docker-compose** file that wires them together with Kafka for local development.

```
┌──────────────────────────────────────────────────────────────┐
│                    docker compose up                         │
│                                                              │
│  ┌─────────────────┐        ┌──────────────────────────┐    │
│  │  transaction-   │        │    fraud-detection       │    │
│  │  producer       │──────▶ │    :8080                 │    │
│  │  (no HTTP port) │        │    SQLite → /data vol    │    │
│  └────────┬────────┘        └────────────┬─────────────┘    │
│           │  Kafka topic:               │                   │
│           │  transaction.created        │                   │
│           ▼                             ▼                   │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                  kafka (KRaft)                      │    │
│  │                  bitnami/kafka:3.7                  │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │          kafka-ui  →  localhost:8090                │    │
│  └─────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

---

## The Dockerfile — Multi-Stage Builds

Both services use a **multi-stage Dockerfile**. This is a best practice for Java applications.

### Why Multi-Stage?

A naive approach would install Maven, download all dependencies, compile, and leave everything in the final image. The resulting image would be ~700 MB. With multi-stage builds, the final image only contains the compiled JAR and a minimal JRE — typically ~200 MB.

### How It Works (fraud-detection-service as example)

```dockerfile
# ── Stage 1: BUILD ──────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17-alpine AS builder   # Full JDK + Maven
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q                       # Cache dependencies as a layer
COPY src ./src
RUN mvn package -DskipTests -q                         # Compile → produces target/*.jar

# ── Stage 2: RUNTIME ────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine                    # Tiny JRE only (~85 MB)
WORKDIR /app
COPY --from=builder /app/target/fraud-detection-service-*.jar app.jar
ENV KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    DB_PATH=/data/fraud_engine.db
EXPOSE 8080
VOLUME /data                                           # Declares /data as a mountable path
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Stage 1 (`builder`)** — uses the full Maven + JDK image. Maven downloads all dependencies, then compiles the source code into a fat JAR. This stage is discarded after the build; it never ships.

**Stage 2 (runtime)** — starts completely fresh from a slim JRE-only base. It copies in only the compiled JAR from Stage 1 (`COPY --from=builder`). No Maven, no JDK, no source code reaches the final image.

### Layer Caching — Why `pom.xml` is Copied First

```dockerfile
COPY pom.xml .
RUN mvn dependency:go-offline -q   ← cached as a layer
COPY src ./src
RUN mvn package -DskipTests -q
```

Docker caches each instruction as a layer. By copying `pom.xml` and downloading dependencies *before* copying source code, Docker only re-downloads dependencies when `pom.xml` changes. If only `src/` changes (the common case during development), the dependency layer is served from cache and the rebuild is fast.

---

## How the Two Images Are Built

### What Triggers a Build

There are two paths that produce an image:

**Local dev — `docker compose up --build`**

Docker Compose reads `docker-compose.yml`, sees `build: ./transaction-producer-service` and `build: ./fraud-detection-service`, and runs the Dockerfile in each directory. The resulting images are stored in Docker's local image cache on your machine and never touch a registry.

```
docker compose up --build
        │
        ├── build: ./transaction-producer-service  →  runs that Dockerfile
        └── build: ./fraud-detection-service       →  runs that Dockerfile
```

**Kubernetes — manual build + push**

The k8s manifest references image names but does not build them. You build and push separately before applying the manifest:

```bash
docker build -t fraud-rule-engine/transaction-producer:latest ./transaction-producer-service
docker build -t fraud-rule-engine/fraud-detection:latest ./fraud-detection-service

# Push to your registry (update prefix to match your environment)
docker push fraud-rule-engine/transaction-producer:latest
docker push fraud-rule-engine/fraud-detection:latest

# Then deploy
kubectl apply -f k8s/java-services.yaml
```

---

### What Each Build Does — Step by Step

Both Dockerfiles follow the same two-stage pattern. Here is the exact sequence Docker executes for `fraud-detection-service`:

```
STEP 1  FROM maven:3.9-eclipse-temurin-17-alpine AS builder
        Pull base image with JDK 17 + Maven 3.9 pre-installed (~500 MB)

STEP 2  WORKDIR /app
        Create and enter the /app working directory inside the container

STEP 3  COPY pom.xml .
        Copy only the dependency manifest — NOT source code yet

STEP 4  RUN mvn dependency:go-offline -q
        Download all Maven dependencies into the layer cache.
        This layer is only re-run when pom.xml changes.

STEP 5  COPY src ./src
        Now copy source code into the builder container

STEP 6  RUN mvn package -DskipTests -q
        Compile everything → produces:
        target/fraud-detection-service-0.0.1-SNAPSHOT.jar  (~50 MB fat JAR)
        Stage 1 is now complete. Its filesystem contains the JAR.

──── Stage 1 is discarded here — it never becomes part of the final image ────

STEP 7  FROM eclipse-temurin:17-jre-alpine
        Start completely fresh from a minimal JRE-only base (~85 MB).
        No Maven. No JDK. No source code.

STEP 8  WORKDIR /app
        Create working directory in the new runtime container

STEP 9  COPY --from=builder /app/target/fraud-detection-service-*.jar app.jar
        Reach back into Stage 1's filesystem and copy ONLY the compiled JAR.
        Nothing else from Stage 1 is carried forward.

STEP 10 ENV / EXPOSE / VOLUME / ENTRYPOINT
        Set default environment variables, declare port 8080,
        declare /data as a mountable volume path,
        set the startup command: java -jar app.jar
```

The `transaction-producer-service` follows the identical sequence, with no `EXPOSE` or `VOLUME` since it has no HTTP server and writes no files.

---

### What the Final Images Contain

| | `transaction-producer` | `fraud-detection` |
|---|---|---|
| **Base OS** | Alpine Linux (musl libc) | Alpine Linux (musl libc) |
| **Runtime** | Eclipse Temurin JRE 17 | Eclipse Temurin JRE 17 |
| **Application** | `app.jar` (fat JAR ~33 MB) | `app.jar` (fat JAR ~50 MB) |
| **Exposed port** | None | `8080` |
| **Declared volume** | None | `/data` |
| **Maven / JDK** | ✗ not present | ✗ not present |
| **Source code** | ✗ not present | ✗ not present |
| **Approx total size** | ~200 MB | ~210 MB |

The fat JAR produced by `spring-boot-maven-plugin` already has Spring Boot, Kafka, Jackson, SQLite JDBC, and Hibernate bundled inside it. The runtime image needs nothing beyond a JRE to execute it.

---

## The .dockerignore File

The `.dockerignore` file tells Docker which files to exclude from the build context (the files sent to the Docker daemon before building):

```
target/       ← compiled output — Stage 1 builds this fresh; sending it wastes time
*.db          ← local SQLite database file — must not end up in the image
.DS_Store     ← macOS metadata
.git          ← version history — not needed at runtime
```

Without `.dockerignore`, Docker would send the entire project directory (including the 33 MB pre-built JAR in `target/`) to the daemon on every build, and that stale JAR could potentially interfere with the Maven build stage.

---

## The docker-compose File

`docker-compose.yml` describes the complete local development environment as code. Running one command (`docker compose up --build`) starts all four services in the correct order.

### Service Dependency and Health Checks

```yaml
kafka:
  healthcheck:
    test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"]
    interval: 10s
    retries: 6
    start_period: 15s

transaction-producer:
  depends_on:
    kafka:
      condition: service_healthy   ← waits for Kafka to pass its health check
```

Both application services use `condition: service_healthy` rather than just `depends_on: kafka`. Without this, Docker would start the services the moment the Kafka *container* starts, but Kafka itself takes ~10 seconds to become ready. The services would crash-loop until Kafka was up. `service_healthy` solves this cleanly.

### Environment Variable Injection

```yaml
fraud-detection:
  environment:
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092   ← "kafka" resolves to the kafka container's IP
    DB_PATH: /data/fraud_engine.db
```

Inside a Docker Compose network, service names act as DNS hostnames. `kafka:9092` routes to the Kafka container. This is why the application properties use `${KAFKA_BOOTSTRAP_SERVERS:localhost:30092}` — `localhost:30092` is the NodePort for direct local runs; `kafka:9092` is the in-container address.

### Named Volume for SQLite Persistence

```yaml
fraud-detection:
  volumes:
    - fraud-db:/data        ← mounts the named volume at /data inside the container

volumes:
  fraud-db:                 ← Docker manages this volume's lifecycle
```

SQLite stores its database in a single file (`fraud_engine.db`). Inside a container, the filesystem is ephemeral — if the container is removed, the DB is gone. A **named volume** (`fraud-db`) is a persistent storage area managed by Docker that survives `docker compose down`. The container writes to `/data/fraud_engine.db`; Docker stores it on the host at a path like `/var/lib/docker/volumes/fraud-db/_data/`.

### Kafka — KRaft Mode (No Zookeeper)

The project uses `bitnami/kafka:3.7` in **KRaft mode**, which is Kafka's native consensus protocol introduced in Kafka 3.x. This replaces the older Zookeeper dependency, meaning only one container is needed for Kafka instead of two. The key settings:

```yaml
KAFKA_CFG_PROCESS_ROLES: broker,controller    # This node acts as both broker and controller
KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092  # How clients reach the broker
KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: "true"   # transaction.created topic is auto-created
```

---

## The Kubernetes Manifest Updates

For cluster deployments, `k8s/java-services.yaml` was updated with three additions:

### 1. Real Image Names
```yaml
# Before
image: replace-with-your-transaction-producer-image

# After
image: fraud-rule-engine/transaction-producer:latest
```
Replace `fraud-rule-engine/` with your registry prefix (e.g. `harbor.internal/fraud-rule-engine/`) before applying.

### 2. DB_PATH Environment Variable
```yaml
env:
  - name: DB_PATH
    value: "/data/fraud_engine.db"
```
Without this, the fraud-detection pod defaults `DB_PATH` to `./fraud_engine.db`, which writes the database inside the container's working directory. That path is not backed by a volume and is lost on every pod restart.

### 3. PersistentVolumeClaim + VolumeMount
```yaml
# New PVC — requests 1 GB of persistent storage from the cluster
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: fraud-db-pvc
spec:
  accessModes: [ReadWriteOnce]   # One pod at a time (correct for SQLite)
  resources:
    requests:
      storage: 1Gi
```
```yaml
# In the Deployment — mounts the PVC at /data inside the container
volumeMounts:
  - name: fraud-db
    mountPath: /data
volumes:
  - name: fraud-db
    persistentVolumeClaim:
      claimName: fraud-db-pvc
```

`ReadWriteOnce` is the correct access mode for SQLite — it can only be safely written by one pod at a time. This also aligns with the `replicas: 1` setting on the deployment.

---

## Running the Stack

```bash
# Start everything (builds images on first run)
docker compose up --build

# Start in detached mode (background)
docker compose up --build -d

# View logs for a specific service
docker compose logs -f fraud-detection

# Stop and remove containers (volume is preserved)
docker compose down

# Stop and remove containers AND the SQLite volume
docker compose down -v

# Rebuild a single service after a code change
docker compose up --build fraud-detection
```

### Endpoints once running

| Service | URL |
|---------|-----|
| Fraud Detection REST API | `http://localhost:8080/api/stats` |
| Fraud Detection transactions | `http://localhost:8080/api/transactions` |
| Kafka UI | `http://localhost:8090` |