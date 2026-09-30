package com.fleetpulse.streamengine;

import com.fleetpulse.streamengine.model.AlertEvent;
import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.model.GeofenceEvent;
import com.fleetpulse.streamengine.processor.GeofenceProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GeofenceProcessorTest {

    private GeofenceProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new GeofenceProcessor(
            300.0,   // towDisplacementThresholdM
            60_000L, // towWindowMs
            new SimpleMeterRegistry()
        );
    }

    private CanonicalTelemetryEvent makeEvent(String pid, long ts, int latE6, int lonE6, boolean ignition) {
        return new CanonicalTelemetryEvent(
            "evt-" + ts,
            pid,
            1,
            "TESLA",
            1,
            ts,
            ts + 10,
            1L,
            latE6,
            lonE6,
            0,
            0.0f,
            1000.0,
            ignition,
            null,
            80.0f,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            0
        );
    }

    @Test
    @DisplayName("Geofence ENTER and EXIT transitions with 2-sample hysteresis (§7.17, §8.S2)")
    void testGeofenceEnterAndExit() {
        String pid = "veh-depot-user";
        long t = 1_700_000_000_000L;

        // Depot 101 center is 37.774929, -122.419416 (radius 500m)
        // 1. Outside depot (far away at lat: 37.850000)
        processor.processEvent(makeEvent(pid, t, 37_850_000, -122_419_416, true));

        // 2. Sample 1 inside depot (37.774929, -122.419416)
        t += 1000L;
        GeofenceProcessor.GeofenceResult r1 = processor.processEvent(makeEvent(pid, t, 37_774_929, -122_419_416, true));
        assertTrue(r1.geofenceEvent().isEmpty(), "Single sample must not trigger transition (hysteresis)");

        // 3. Sample 2 inside depot -> ENTER event triggers!
        t += 1000L;
        GeofenceProcessor.GeofenceResult r2 = processor.processEvent(makeEvent(pid, t, 37_774_929, -122_419_416, true));
        assertTrue(r2.geofenceEvent().isPresent(), "Confirmed inside triggers ENTER transition");
        GeofenceEvent enterEvent = r2.geofenceEvent().get();
        assertEquals("ENTER", enterEvent.transition());
        assertEquals(101, enterEvent.geofenceId());

        // 4. Vehicle leaves depot: Sample 1 outside
        t += 1000L;
        GeofenceProcessor.GeofenceResult r3 = processor.processEvent(makeEvent(pid, t, 37_850_000, -122_419_416, true));
        assertTrue(r3.geofenceEvent().isEmpty(), "Single sample outside must not trigger EXIT yet");

        // 5. Sample 2 outside -> EXIT event triggers!
        t += 1000L;
        GeofenceProcessor.GeofenceResult r4 = processor.processEvent(makeEvent(pid, t, 37_850_000, -122_419_416, true));
        assertTrue(r4.geofenceEvent().isPresent(), "Confirmed outside triggers EXIT transition");
        assertEquals("EXIT", r4.geofenceEvent().get().transition());
    }

    @Test
    @DisplayName("Tow Suspected rule generates CRITICAL alert when displaced > 300m while ignition is OFF (§7.17)")
    void testTowSuspectedAlert() {
        String pid = "veh-parked-target";
        long t = 1_700_000_000_000L;
        int parkLat = 37_774_929;
        int parkLon = -122_419_416;

        // 1. Park vehicle with ignition OFF
        processor.processEvent(makeEvent(pid, t, parkLat, parkLon, false));

        // 2. 20 seconds later, vehicle has been towed 600m away (lat + 6000 microdeg ~ 660m)
        t += 20_000L;
        int towLat = parkLat + 6000;
        GeofenceProcessor.GeofenceResult r = processor.processEvent(makeEvent(pid, t, towLat, parkLon, false));

        assertTrue(r.alertEvent().isPresent(), "TOW_SUSPECTED alert must be emitted");
        AlertEvent alert = r.alertEvent().get();
        assertEquals("TOW_SUSPECTED", alert.type());
        assertEquals("CRITICAL", alert.severity());
        assertEquals(pid, alert.vehiclePid());
    }
}
