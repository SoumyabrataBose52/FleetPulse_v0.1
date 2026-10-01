# Multi-stage Dockerfile for FleetPulse Normalizer Service
# Stage 1: Build with Maven
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Cache dependency layer
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Compile application JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime JRE
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
RUN addgroup --system fleetpulse && adduser --system --ingroup fleetpulse fleetpulse
USER fleetpulse
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8082
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-XX:+EnableDynamicAgentLoading", "-jar", "app.jar"]
