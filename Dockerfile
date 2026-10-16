# Build Stage
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Compile and package application
COPY src ./src
RUN mvn clean package -DskipTests -B

# Runtime Stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Create non-root system user and group
RUN groupadd -r ledger && useradd -r -g ledger ledger

# Copy artifact from build stage
COPY --from=builder /workspace/target/distributed-financial-ledger-*.jar app.jar
RUN chown -R ledger:ledger /app

USER ledger

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
