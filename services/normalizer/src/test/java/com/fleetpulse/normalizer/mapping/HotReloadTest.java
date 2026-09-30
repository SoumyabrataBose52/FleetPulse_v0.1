package com.fleetpulse.normalizer.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpulse.normalizer.engine.NormalizationResult;
import com.fleetpulse.normalizer.engine.NormalizerEngine;
import com.fleetpulse.normalizer.model.DlqReason;
import com.fleetpulse.normalizer.model.VehicleMetadata;
import com.fleetpulse.normalizer.registry.VehicleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §15.6 Hot-Reload Acceptance Test.
 *
 * Demonstrates activating a new OEM mapping version mid-stream with zero restarts and zero DLQ.
 */
class HotReloadTest {

    private MappingRegistry registry;
    private NormalizerEngine engine;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        MappingCompiler compiler = new MappingCompiler(objectMapper);
        registry = new MappingRegistry(compiler);

        VehicleRegistry vehicleRegistry = new VehicleRegistry();
        vehicleRegistry.register(new VehicleMetadata(
            "test-pid-123",
            1,
            "1HGCR2F83HA000001",
            1,
            1
        ));

        // Initial mapping for OEM X v1
        String oemXv1Json = """
            {
              "oem": "X",
              "schema_ver": 1,
              "format": "json",
              "vin_field": "vin",
              "fields": {
                "ts_event": { "source": "t", "transform": "direct", "required": true },
                "speed_kmh": { "source": "spd", "transform": "direct" }
              }
            }
            """;
        registry.registerJson(oemXv1Json);

        engine = new NormalizerEngine(registry, vehicleRegistry, objectMapper, false);
    }

    @Test
    void testHotReloadNewVersionMidStream() {
        // 1. Process OEM X v1 payload -> Success
        String v1Payload = "{\"vin\":\"1HGCR2F83HA000001\",\"t\":1790331302120,\"spd\":60.0}";
        NormalizationResult res1 = engine.normalize(v1Payload, "X", 1, 1790331305000L, Collections.emptyMap());
        assertTrue(res1.success());
        assertEquals(1, res1.canonicalEvent().schemaVer());
        assertEquals(60.0f, res1.canonicalEvent().speedKmh());

        // 2. Process OEM X v2 payload before v2 mapping registered -> DLQ UNKNOWN_OEM
        String v2Payload = "{\"vin\":\"1HGCR2F83HA000001\",\"t\":1790331302120,\"velocity_ms\":25.0}";
        NormalizationResult res2 = engine.normalize(v2Payload, "X", 2, 1790331305000L, Collections.emptyMap());
        assertFalse(res2.success());
        assertEquals(DlqReason.UNKNOWN_OEM, res2.dlqEvent().reasonCode());

        // 3. Hot-reload new mapping for OEM X v2 dynamically mid-stream
        String oemXv2Json = """
            {
              "oem": "X",
              "schema_ver": 2,
              "format": "json",
              "vin_field": "vin",
              "fields": {
                "ts_event": { "source": "t", "transform": "direct", "required": true },
                "speed_kmh": { "source": "velocity_ms", "transform": "scale", "factor": 3.6 }
              }
            }
            """;
        registry.registerJson(oemXv2Json);

        // 4. Process OEM X v2 payload again -> Now succeeds immediately with zero restart!
        NormalizationResult res3 = engine.normalize(v2Payload, "X", 2, 1790331305000L, Collections.emptyMap());
        assertTrue(res3.success());
        assertEquals(2, res3.canonicalEvent().schemaVer());
        assertEquals(90.0f, res3.canonicalEvent().speedKmh(), 0.01f); // 25 m/s * 3.6 = 90 km/h
    }
}
