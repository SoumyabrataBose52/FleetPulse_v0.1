package com.fleetpulse.normalizer.controller;

import com.fleetpulse.normalizer.mapping.MappingRegistry;
import com.fleetpulse.normalizer.registry.VehicleRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * §15.1 & §9 Normalizer Health and Readiness Probes.
 */
@RestController
public class HealthController {

    private final MappingRegistry mappingRegistry;
    private final VehicleRegistry vehicleRegistry;

    public HealthController(MappingRegistry mappingRegistry, VehicleRegistry vehicleRegistry) {
        this.mappingRegistry = mappingRegistry;
        this.vehicleRegistry = vehicleRegistry;
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
        int activeMappings = mappingRegistry.getAllActive().size();
        if (activeMappings == 0) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "DOWN",
                "reason", "No active OEM mappings registered in registry"
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", "READY",
            "active_mappings", activeMappings,
            "cached_vehicles", vehicleRegistry.size(),
            "timestamp", Instant.now().toString()
        ));
    }
}
