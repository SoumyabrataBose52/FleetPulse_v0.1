package com.fleetpulse.normalizer.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpulse.normalizer.mapping.MappingCompiler;
import com.fleetpulse.normalizer.mapping.MappingRegistry;
import com.fleetpulse.normalizer.model.DlqReason;
import com.fleetpulse.normalizer.model.VehicleMetadata;
import com.fleetpulse.normalizer.registry.VehicleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class NormalizerEngineTest {

    private MappingRegistry registry;
    private VehicleRegistry vehicleRegistry;
    private ObjectMapper objectMapper;
    private NormalizerEngine engine;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        MappingCompiler compiler = new MappingCompiler(objectMapper);
        registry = new MappingRegistry(compiler);

        String oemAJson = """
            {
              "oem": "A",
              "schema_ver": 1,
              "format": "json",
              "vin_field": "vehicleId",
              "fields": {
                "ts_event": { "source": "timestamp", "transform": "iso8601_to_ms", "required": true },
                "speed_kmh": { "source": "speedKph", "transform": "direct" },
                "seq": { "source": "seq", "transform": "direct" }
              }
            }
            """;
        registry.registerJson(oemAJson);

        vehicleRegistry = new VehicleRegistry();
        vehicleRegistry.register(new VehicleMetadata(
            "pid-uuid-001",
            1,
            "1HGCR2F83HA000001",
            1,
            1
        ));

        engine = new NormalizerEngine(registry, vehicleRegistry, objectMapper, false);
    }

    @Test
    void testMalformedJsonRoutesToDlq() {
        String badJson = "{vehicleId: 1HGCR2F83HA000001, broken json";
        NormalizationResult result = engine.normalize(badJson, "A", 1, 1000L, Collections.emptyMap());

        assertFalse(result.success());
        assertEquals(DlqReason.MALFORMED, result.dlqEvent().reasonCode());
        assertTrue(result.dlqEvent().errorMessage().contains("Malformed JSON"));
    }

    @Test
    void testUnknownOemRoutesToDlq() {
        String payload = "{\"vehicleId\":\"1HGCR2F83HA000001\",\"timestamp\":\"2026-09-25T10:15:02Z\"}";
        NormalizationResult result = engine.normalize(payload, "UNKNOWN_OEM", 1, 1000L, Collections.emptyMap());

        assertFalse(result.success());
        assertEquals(DlqReason.UNKNOWN_OEM, result.dlqEvent().reasonCode());
    }

    @Test
    void testInvalidVinRoutesToDlq() {
        // VIN contains forbidden letter 'I'
        String payload = "{\"vehicleId\":\"1HGCR2F83HA00000I\",\"timestamp\":\"2026-09-25T10:15:02Z\"}";
        NormalizationResult result = engine.normalize(payload, "A", 1, 1000L, Collections.emptyMap());

        assertFalse(result.success());
        assertEquals(DlqReason.INVALID_VIN, result.dlqEvent().reasonCode());
    }

    @Test
    void testUnknownVehicleRoutesToDlq() {
        // Valid VIN format, but not in inventory registry
        String payload = "{\"vehicleId\":\"5YJCR2F836A100001\",\"timestamp\":\"2026-09-25T10:15:02Z\"}";
        NormalizationResult result = engine.normalize(payload, "A", 1, 1000L, Collections.emptyMap());

        assertFalse(result.success());
        assertEquals(DlqReason.UNKNOWN_VEHICLE, result.dlqEvent().reasonCode());
    }

    @Test
    void testMissingRequiredFieldRoutesToDlq() {
        // Missing timestamp field
        String payload = "{\"vehicleId\":\"1HGCR2F83HA000001\",\"speedKph\":50}";
        NormalizationResult result = engine.normalize(payload, "A", 1, 1000L, Collections.emptyMap());

        assertFalse(result.success());
        assertEquals(DlqReason.MAPPING_ERROR, result.dlqEvent().reasonCode());
        assertTrue(result.dlqEvent().errorMessage().contains("Required field missing: ts_event"));
    }

    @Test
    void testVinCheckDigitWarningNonStrictMode() {
        // Check digit at index 8 changed from '3' to '9'
        String warnVin = "1HGCR2F89HA000001";
        vehicleRegistry.register(new VehicleMetadata("pid-warn", 1, warnVin, 1, 1));

        String payload = "{\"vehicleId\":\"" + warnVin + "\",\"timestamp\":\"2026-09-25T10:15:02.000Z\"}";
        NormalizationResult result = engine.normalize(payload, "A", 1, 1790331302000L, Collections.emptyMap());

        assertTrue(result.success());
        // Bit 8 must be set (VIN_WARN)
        assertEquals(NormalizerEngine.QUALITY_VIN_WARN, result.canonicalEvent().quality() & NormalizerEngine.QUALITY_VIN_WARN);
    }

    @Test
    void testVinCheckDigitStrictModeRoutesToDlq() {
        NormalizerEngine strictEngine = new NormalizerEngine(registry, vehicleRegistry, objectMapper, true);

        String warnVin = "1HGCR2F89HA000001";
        vehicleRegistry.register(new VehicleMetadata("pid-warn", 1, warnVin, 1, 1));

        String payload = "{\"vehicleId\":\"" + warnVin + "\",\"timestamp\":\"2026-09-25T10:15:02.000Z\"}";
        NormalizationResult result = strictEngine.normalize(payload, "A", 1, 1790331302000L, Collections.emptyMap());

        assertFalse(result.success());
        assertEquals(DlqReason.INVALID_VIN, result.dlqEvent().reasonCode());
    }

    @Test
    void testClockSkewFlagged() {
        long recvTs = 1790331302000L;
        // Event is 15 minutes in the future (> 10 min threshold)
        long futureTs = recvTs + 15 * 60 * 1000L;
        String futureIso = java.time.Instant.ofEpochMilli(futureTs).toString();

        String payload = "{\"vehicleId\":\"1HGCR2F83HA000001\",\"timestamp\":\"" + futureIso + "\"}";
        NormalizationResult result = engine.normalize(payload, "A", 1, recvTs, Collections.emptyMap());

        assertTrue(result.success());
        assertEquals(NormalizerEngine.QUALITY_CLOCK_SKEW, result.canonicalEvent().quality() & NormalizerEngine.QUALITY_CLOCK_SKEW);
    }

    @Test
    void testDeterministicEventId() {
        String payload = "{\"vehicleId\":\"1HGCR2F83HA000001\",\"timestamp\":\"2026-09-25T10:15:02.000Z\",\"seq\":101}";
        NormalizationResult r1 = engine.normalize(payload, "A", 1, 1790331302000L, Collections.emptyMap());
        NormalizationResult r2 = engine.normalize(payload, "A", 1, 1790331302000L, Collections.emptyMap());

        assertTrue(r1.success());
        assertTrue(r2.success());
        assertEquals(r1.canonicalEvent().eventId(), r2.canonicalEvent().eventId());
    }
}
