package com.fleetpulse.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * §4.4 & §15.5 FleetPulse Ingestion Gateway Application.
 *
 * Provides high-throughput, back-pressure-aware HTTP NDJSON telematics intake
 * with mTLS verification and asynchronous batching Kafka producers.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
