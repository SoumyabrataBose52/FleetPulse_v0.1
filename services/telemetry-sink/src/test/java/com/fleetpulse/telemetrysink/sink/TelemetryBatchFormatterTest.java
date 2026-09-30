package com.fleetpulse.telemetrysink.sink;

import com.fleetpulse.telemetrysink.model.CanonicalTelemetryEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryBatchFormatterTest {

    @Test
    void testToTabSeparatedWithFullFields() {
        CanonicalTelemetryEvent event = new CanonicalTelemetryEvent(
            "00000000000000ff",
            "11111111-2222-3333-4444-555555555555",
            42,
            "B",
            1,
            1700000000000L,
            1700000000100L,
            555L,
            40712800,
            -74006000,
            90,
            50.5f,
            25000.5,
            true,
            80.0f,
            75.5f,
            350.0f,
            "CHARGING",
            22.0f,
            90.0f,
            1800,
            List.of("P0300", "C0035"),
            "HARSH_BRAKE",
            0
        );

        String tsv = TelemetryBatchFormatter.toTabSeparated(List.of(event));
        assertNotNull(tsv);
        assertTrue(tsv.endsWith("\n"));

        String[] cols = tsv.trim().split("\t");
        assertEquals(22, cols.length, "ClickHouse table has 22 columns");

        // 1. tenant_id
        assertEquals("42", cols[0]);
        // 2. vehicle_pid
        assertEquals("11111111-2222-3333-4444-555555555555", cols[1]);
        // 4. event_id (UInt64)
        assertEquals("255", cols[3]);
        // 5. seq
        assertEquals("555", cols[4]);
        // 6. lat_e6
        assertEquals("40712800", cols[5]);
        // 7. lon_e6
        assertEquals("-74006000", cols[6]);
        // 8. heading_deg
        assertEquals("90", cols[7]);
        // 9. speed_kmh
        assertEquals("50.5", cols[8]);
        // 10. odo_km
        assertEquals("25000.5", cols[9]);
        // 11. ignition
        assertEquals("1", cols[10]);
        // 15. charge_state
        assertEquals("CHARGING", cols[14]);
        // 19. dtc array
        assertEquals("['P0300','C0035']", cols[18]);
        // 20. evt
        assertEquals("HARSH_BRAKE", cols[19]);
        // 21. quality
        assertEquals("0", cols[20]);
    }

    @Test
    void testToTabSeparatedWithNullFields() {
        CanonicalTelemetryEvent event = new CanonicalTelemetryEvent(
            "100",
            "11111111-2222-3333-4444-555555555555",
            1,
            "C",
            1,
            1700000000000L,
            1700000000100L,
            null, // seq
            null, // lat_e6
            null, // lon_e6
            null, // heading
            null, // speed
            null, // odo
            null, // ignition
            null, // fuel
            null, // soc
            null, // batt_voltage
            null, // charge_state
            null, // charge_kw
            null, // coolant_c
            null, // rpm
            List.of(), // dtc
            null, // evt
            0
        );

        String tsv = TelemetryBatchFormatter.toTabSeparated(List.of(event));
        String[] cols = tsv.trim().split("\t");

        assertEquals(22, cols.length);
        assertEquals("\\N", cols[4], "seq must be \\N");
        assertEquals("\\N", cols[5], "lat_e6 must be \\N");
        assertEquals("\\N", cols[6], "lon_e6 must be \\N");
        assertEquals("\\N", cols[10], "ignition must be \\N");
        assertEquals("[]", cols[18], "empty dtc array must be []");
        assertEquals("\\N", cols[19], "evt must be \\N");
    }

    @Test
    void testEmptyBatch() {
        assertEquals("", TelemetryBatchFormatter.toTabSeparated(List.of()));
        assertEquals("", TelemetryBatchFormatter.toTabSeparated(null));
    }
}
