package com.fleetpulse.insightsink.model;

import org.apache.avro.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelSerializationTest {

    private static Schema tripSchema;
    private static Schema alertSchema;

    @BeforeAll
    static void setUp() throws Exception {
        ClassPathResource tripRes = new ClassPathResource("schemas/trip_event.avsc");
        try (InputStream is = tripRes.getInputStream()) {
            tripSchema = new Schema.Parser().parse(is);
        }

        ClassPathResource alertRes = new ClassPathResource("schemas/alert.avsc");
        try (InputStream is = alertRes.getInputStream()) {
            alertSchema = new Schema.Parser().parse(is);
        }
    }

    @Test
    void testTripEventRecordRoundTrip() throws Exception {
        TripEventRecord original = new TripEventRecord(
            "trip-uuid-1234",
            "veh-uuid-5678",
            10,
            101,
            1700000000000L,
            1700000003600L,
            37774900,
            -122419400,
            "9q8yyk1",
            37775000,
            -122419500,
            "9q8yyk2",
            15.5,
            3600,
            120,
            3.5,
            null,
            2.50,
            0.0,
            "COMPLETED"
        );

        byte[] bytes = original.toAvroBinary(tripSchema);
        assertNotNull(bytes);

        TripEventRecord deserialized = TripEventRecord.fromAvroBinary(bytes, tripSchema);
        assertEquals(original.tripId(), deserialized.tripId());
        assertEquals(original.vehiclePid(), deserialized.vehiclePid());
        assertEquals(original.tenantId(), deserialized.tenantId());
        assertEquals(original.driverId(), deserialized.driverId());
        assertEquals(original.startTs(), deserialized.startTs());
        assertEquals(original.endTs(), deserialized.endTs());
        assertEquals(original.distanceKm(), deserialized.distanceKm(), 0.001);
        assertEquals(original.durationS(), deserialized.durationS());
        assertEquals(original.idleS(), deserialized.idleS());
        assertEquals(original.energyKwh(), deserialized.energyKwh(), 0.001);
        assertNull(deserialized.fuelL());
        assertEquals(original.cost(), deserialized.cost(), 0.001);
        assertEquals(original.status(), deserialized.status());
    }

    @Test
    void testAlertEventRecordRoundTrip() throws Exception {
        AlertEventRecord original = new AlertEventRecord(
            "alert-uuid-1",
            "alert-key-veh1-RANGE_RISK-1700000000",
            10,
            "veh-uuid-5678",
            "RANGE_RISK",
            "HIGH",
            1700000000000L,
            "Vehicle battery critically low (12% remaining)",
            Map.of("soc", "12", "nearest_charger_km", "4.2"),
            "mongo-insight-id-999"
        );

        byte[] bytes = original.toAvroBinary(alertSchema);
        assertNotNull(bytes);

        AlertEventRecord deserialized = AlertEventRecord.fromAvroBinary(bytes, alertSchema);
        assertEquals(original.alertId(), deserialized.alertId());
        assertEquals(original.alertKey(), deserialized.alertKey());
        assertEquals(original.tenantId(), deserialized.tenantId());
        assertEquals(original.vehiclePid(), deserialized.vehiclePid());
        assertEquals(original.type(), deserialized.type());
        assertEquals(original.severity(), deserialized.severity());
        assertEquals(original.ts(), deserialized.ts());
        assertEquals(original.message(), deserialized.message());
        assertEquals("12", deserialized.details().get("soc"));
        assertEquals(original.insightRef(), deserialized.insightRef());
    }
}
