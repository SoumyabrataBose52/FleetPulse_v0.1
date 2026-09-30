package com.fleetpulse.normalizer.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpulse.normalizer.engine.NormalizationResult;
import com.fleetpulse.normalizer.engine.NormalizerEngine;
import com.fleetpulse.normalizer.model.CanonicalTelemetryEvent;
import com.fleetpulse.normalizer.model.VehicleMetadata;
import com.fleetpulse.normalizer.registry.VehicleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §5.2 & §15.6 Golden Sample Suite.
 *
 * Verifies that all 5 OEM formats (+ Echo v1 and v2) normalize exactly
 * into the expected canonical telemetry structures with zero loss or drift.
 */
class GoldenSamplesTest {

    private NormalizerEngine engine;
    private JsonNode goldenData;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MappingCompiler compiler = new MappingCompiler(objectMapper);
        MappingRegistry registry = new MappingRegistry(compiler);

        Path mappingsDir = findDir("config/oem-mappings");
        registry.loadFromDirectory(mappingsDir);

        VehicleRegistry vehicleRegistry = new VehicleRegistry();
        // Register golden vehicle
        vehicleRegistry.register(new VehicleMetadata(
            "703092ac-8f03-4fc7-af76-0c416776ff24",
            1,
            "1HGCR2F83HA000001",
            5,
            1
        ));

        engine = new NormalizerEngine(registry, vehicleRegistry, objectMapper, false);

        Path goldenPath = mappingsDir.resolve("golden_samples.json");
        String goldenJson = Files.readString(goldenPath);
        goldenData = objectMapper.readTree(goldenJson);
    }

    @Test
    void testGoldenOemA() {
        verifyGolden("oem_a", "A", 1);
    }

    @Test
    void testGoldenOemB() {
        verifyGolden("oem_b", "B", 1);
    }

    @Test
    void testGoldenOemC() {
        verifyGolden("oem_c", "C", 1);
    }

    @Test
    void testGoldenOemD() {
        verifyGolden("oem_d", "D", 1);
    }

    @Test
    void testGoldenOemEV1() {
        verifyGolden("oem_e_v1", "E", 1);
    }

    @Test
    void testGoldenOemEV2() {
        verifyGolden("oem_e_v2", "E", 2);
    }

    private void verifyGolden(String goldenKey, String oem, int schemaVer) {
        JsonNode testCase = goldenData.get(goldenKey);
        assertNotNull(testCase, "Golden test case missing: " + goldenKey);

        JsonNode rawNode = testCase.get("raw");
        String rawPayload = rawNode.isTextual() ? rawNode.asText() : rawNode.toString();
        JsonNode expected = testCase.get("expected");

        long now = 1790331305000L;
        NormalizationResult result = engine.normalize(rawPayload, oem, schemaVer, now, Collections.emptyMap());

        assertTrue(result.success(), "Normalization failed for " + goldenKey + ": " +
            (result.dlqEvent() != null ? result.dlqEvent().errorMessage() : "unknown"));

        CanonicalTelemetryEvent event = result.canonicalEvent();
        assertNotNull(event);

        assertEquals("703092ac-8f03-4fc7-af76-0c416776ff24", event.vehiclePid());
        assertEquals(1, event.tenantId());
        assertEquals(oem, event.oem());
        assertEquals(schemaVer, event.schemaVer());

        if (expected.has("ts_event")) {
            assertEquals(expected.get("ts_event").asLong(), event.tsEvent());
        }
        if (expected.has("lat_e6")) {
            assertEquals(expected.get("lat_e6").asInt(), event.latE6());
        }
        if (expected.has("lon_e6")) {
            assertEquals(expected.get("lon_e6").asInt(), event.lonE6());
        }
        if (expected.has("heading_deg")) {
            assertEquals(expected.get("heading_deg").asInt(), event.headingDeg());
        }
        if (expected.has("speed_kmh")) {
            assertEquals(expected.get("speed_kmh").asDouble(), event.speedKmh(), 0.05);
        }
        if (expected.has("odo_km")) {
            assertEquals(expected.get("odo_km").asDouble(), event.odoKm(), 0.05);
        }
        if (expected.has("ignition")) {
            assertEquals(expected.get("ignition").asBoolean(), event.ignition());
        }
        if (expected.has("fuel_pct")) {
            assertEquals(expected.get("fuel_pct").asDouble(), event.fuelPct(), 0.05);
        }
        if (expected.has("soc_pct")) {
            assertEquals(expected.get("soc_pct").asDouble(), event.socPct(), 0.05);
        }
        if (expected.has("charge_kw")) {
            assertEquals(expected.get("charge_kw").asDouble(), event.chargeKw(), 0.05);
        }
        if (expected.has("charge_state")) {
            assertEquals(expected.get("charge_state").asText(), event.chargeState());
        }
        if (expected.has("batt_voltage_v")) {
            assertEquals(expected.get("batt_voltage_v").asDouble(), event.battVoltageV(), 0.05);
        }
        if (expected.has("evt")) {
            assertEquals(expected.get("evt").asText(), event.evt());
        }
        if (expected.has("seq")) {
            assertEquals(expected.get("seq").asLong(), event.seq());
        }
        if (expected.has("dtc")) {
            JsonNode dtcArray = expected.get("dtc");
            assertEquals(dtcArray.size(), event.dtc().size());
            for (int i = 0; i < dtcArray.size(); i++) {
                assertEquals(dtcArray.get(i).asText(), event.dtc().get(i));
            }
        }
    }

    private Path findDir(String relative) {
        Path p1 = Paths.get("../../", relative);
        if (Files.exists(p1)) return p1;
        Path p2 = Paths.get(relative);
        if (Files.exists(p2)) return p2;
        throw new IllegalStateException("Directory not found: " + relative);
    }
}
