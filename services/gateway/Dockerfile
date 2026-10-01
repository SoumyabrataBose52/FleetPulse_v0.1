FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN addgroup --system fleetpulse && adduser --system --ingroup fleetpulse fleetpulse
USER fleetpulse
COPY target/gateway-1.0.0.jar app.jar

EXPOSE 8080 8443
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-XX:+EnableDynamicAgentLoading", "-jar", "app.jar"]
