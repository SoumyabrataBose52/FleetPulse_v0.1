package com.fleetpulse.telemetrysink.model;

import org.apache.avro.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalTelemetryEventTest {

    private static Schema schema;

    @BeforeAll
    static void setUp() throws Exception {
        ClassPathResource resource = new ClassPathResource("schemas/canonical_event.avsc");
        try (InputStream is = resource.getInputStream()) {
            schema = new Schema.Parser().parse(is);
        }
    }

    @Test
    void testAvroRoundTrip() throws Exception {
        CanonicalTelemetryEvent original = new CanonicalTelemetryEvent(
            "a1b2c3d4e5f60718",
            "11111111-2222-3333-4444-555555555555",
            101,
            "A",
            1,
            1700000000123L,
            1700000000200L,
            1001L,
            37774900,
            -122419400,
            180,
            65.5f,
            12345.67,
            true,
            75.0f,
            88.5f,
            380.0f,
            "CHARGING",
            45.5f,
            85.0f,
            2500,
            List.of("P0300", "P0171"),
            "HARSH_BRAKE",
            2
        );

        byte[] bytes = original.toAvroBinary(schema);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        CanonicalTelemetryEvent deserialized = CanonicalTelemetryEvent.fromAvroBinary(bytes, schema);

        assertEquals(original.eventId(), deserialized.eventId());
        assertEquals(original.vehiclePid(), deserialized.vehiclePid());
        assertEquals(original.tenantId(), deserialized.tenantId());
        assertEquals(original.oem(), deserialized.oem());
        assertEquals(original.schemaVer(), deserialized.schemaVer());
        assertEquals(original.tsEvent(), deserialized.tsEvent());
        assertEquals(original.tsIngest(), deserialized.tsIngest());
        assertEquals(original.seq(), deserialized.seq());
        assertEquals(original.latE6(), deserialized.latE6());
        assertEquals(original.lonE6(), deserialized.lonE6());
        assertEquals(original.headingDeg(), deserialized.headingDeg());
        assertEquals(original.speedKmh(), deserialized.speedKmh(), 0.001);
        assertEquals(original.odoKm(), deserialized.odoKm(), 0.001);
        assertEquals(original.ignition(), deserialized.ignition());
        assertEquals(original.fuelPct(), deserialized.fuelPct(), 0.001);
        assertEquals(original.socPct(), deserialized.socPct(), 0.001);
        assertEquals(original.battVoltageV(), deserialized.battVoltageV(), 0.001);
        assertEquals(original.chargeState(), deserialized.chargeState());
        assertEquals(original.chargeKw(), deserialized.chargeKw(), 0.001);
        assertEquals(original.coolantC(), deserialized.coolantC(), 0.001);
        assertEquals(original.rpm(), deserialized.rpm());
        assertEquals(original.dtc(), deserialized.dtc());
        assertEquals(original.evt(), deserialized.evt());
        assertEquals(original.quality(), deserialized.quality());
    }

    @Test
    void testGetEventIdAsUInt64() {
        // 16-hex char id
        CanonicalTelemetryEvent hexEvent = new CanonicalTelemetryEvent(
            "00000000000000ff", "pid", 1, "A", 1, 1000L, 1000L,
            1L, 0, 0, 0, 0f, 0.0, false, 0f, 0f, 0f, null, 0f, 0f, 0, List.of(), null, 0
        );
        assertEquals(255L, hexEvent.getEventIdAsUInt64());

        // Decimal string
        CanonicalTelemetryEvent decEvent = new CanonicalTelemetryEvent(
            "1234567890", "pid", 1, "A", 1, 1000L, 1000L,
            1L, 0, 0, 0, 0f, 0.0, false, 0f, 0f, 0f, null, 0f, 0f, 0, List.of(), null, 0
        );
        assertEquals(1234567890L, decEvent.getEventIdAsUInt64());

        // Null / empty
        CanonicalTelemetryEvent emptyEvent = new CanonicalTelemetryEvent(
            "", "pid", 1, "A", 1, 1000L, 1000L,
            1L, 0, 0, 0, 0f, 0.0, false, 0f, 0f, 0f, null, 0f, 0f, 0, List.of(), null, 0
        );
        assertEquals(0L, emptyEvent.getEventIdAsUInt64());
    }

    @Test
    void testFormattedTimestamps() {
        CanonicalTelemetryEvent event = new CanonicalTelemetryEvent(
            "id", "pid", 1, "A", 1, 1700000000123L, 1700000000456L,
            1L, 0, 0, 0, 0f, 0.0, false, 0f, 0f, 0f, null, 0f, 0f, 0, List.of(), null, 0
        );

        String ts = event.getFormattedTs();
        assertTrue(ts.contains(".123"));

        String ingestTs = event.getFormattedIngestTs();
        assertTrue(ingestTs.contains(".456"));
    }
}
