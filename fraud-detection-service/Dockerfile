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