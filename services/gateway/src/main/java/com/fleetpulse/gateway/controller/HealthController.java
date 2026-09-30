package com.fleetpulse.gateway.controller;

import com.fleetpulse.gateway.config.GatewayProperties;
import com.fleetpulse.gateway.service.IngestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * §15.1 & §9 Health and Readiness Probes.
 */
@RestController
public class HealthController {

    private final IngestService ingestService;
    private final GatewayProperties properties;

    public HealthController(IngestService ingestService, GatewayProperties properties) {
        this.ingestService = ingestService;
        this.properties = properties;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> healthz() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readyz() {
        int inFlight = ingestService.getInFlightCount();
        int capacity = properties.queue().capacity();

        if (inFlight >= capacity) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "DOWN",
                "reason", "Ingestion queue at max capacity",
                "in_flight", inFlight
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", "READY",
            "in_flight", inFlight,
            "timestamp", Instant.now().toString()
        ));
    }
}
