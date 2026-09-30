# Multi-stage Dockerfile for FleetPulse Insight Sink Service (§4.5, §5.3, §5.5, §15.8)
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN addgroup --system fleetpulse && adduser --system --ingroup fleetpulse fleetpulse
USER fleetpulse
COPY --from=build /app/target/insight-sink-1.0.0.jar app.jar

EXPOSE 8087
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-jar", "app.jar"]
