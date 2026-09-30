package com.fleetpulse.streamengine.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Health and Readiness Probes (§0.7, §15.9).
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> healthz() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "stream-engine",
            "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readyz() {
        return ResponseEntity.ok(Map.of(
            "status", "READY",
            "service", "stream-engine",
            "timestamp", Instant.now().toString()
        ));
    }
}
