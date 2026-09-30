package com.fleetpulse.telemetrysink.controller;

import com.fleetpulse.telemetrysink.sink.ClickHouseClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final ClickHouseClient clickHouseClient;

    public HealthController(ClickHouseClient clickHouseClient) {
        this.clickHouseClient = clickHouseClient;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, String>> healthz() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "telemetry-sink"));
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readyz() {
        boolean chHealthy = clickHouseClient.ping();
        if (chHealthy) {
            return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "telemetry-sink",
                "clickhouse", "CONNECTED"
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "DOWN",
                "service", "telemetry-sink",
                "clickhouse", "DISCONNECTED"
            ));
        }
    }
}
