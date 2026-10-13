# Stage 1 – build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
# Trust the corporate Zscaler root CA so Maven can reach Central through TLS inspection
COPY zscaler-root-ca.pem /tmp/zscaler-root-ca.pem
RUN keytool -importcert -noprompt -trustcacerts -alias zscaler-root \
    -file /tmp/zscaler-root-ca.pem \
    -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2 – runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/transaction-producer-service-*.jar app.jar
ENV KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    TRANSACTION_TOPIC=transaction.created
ENTRYPOINT ["java", "-jar", "app.jar"]