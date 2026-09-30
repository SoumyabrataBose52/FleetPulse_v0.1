package com.fleetpulse.orderer.fastpath;

import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FastPathProcessorTest {

    private FastPathProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new FastPathProcessor();
    }

    @Test
    void testDrivingStatusDerived() {
        CanonicalTelemetryEvent event = createEvent("pid-1", 1000L, 50.0f, true, null);
        processor.process(event);

        FastPathProcessor.VehicleLiveLocal live = processor.getLocalState("pid-1");
        assertNotNull(live);
        assertEquals("DRIVING", live.status());
        assertEquals(1000L, live.lastTs());
        assertEquals("tf346t", live.geohash6());
    }

    @Test
    void testParkedStatusDerived() {
        CanonicalTelemetryEvent event = createEvent("pid-2", 1000L, 0.0f, false, null);
        processor.process(event);

        FastPathProcessor.VehicleLiveLocal live = processor.getLocalState("pid-2");
        assertNotNull(live);
        assertEquals("PARKED", live.status());
    }

    @Test
    void testChargingStatusDerived() {
        CanonicalTelemetryEvent event = createEvent("pid-3", 1000L, 0.0f, false, "CHARGING");
        processor.process(event);

        FastPathProcessor.VehicleLiveLocal live = processor.getLocalState("pid-3");
        assertNotNull(live);
        assertEquals("CHARGING", live.status());
    }

    @Test
    void testIdleStatusDerived() {
        CanonicalTelemetryEvent event = createEvent("pid-4", 1000L, 0.0f, true, null);
        processor.process(event);

        FastPathProcessor.VehicleLiveLocal live = processor.getLocalState("pid-4");
        assertNotNull(live);
        assertEquals("IDLE", live.status());
    }

    @Test
    void testStaleDropProtectsNewerState() {
        // Newer event at 2000 ms
        CanonicalTelemetryEvent eNew = createEvent("pid-5", 2000L, 60.0f, true, null);
        processor.process(eNew);

        // Older event at 1500 ms arrives late
        CanonicalTelemetryEvent eOld = createEvent("pid-5", 1500L, 10.0f, false, null);
        processor.process(eOld);

        // State must remain the newer one!
        FastPathProcessor.VehicleLiveLocal live = processor.getLocalState("pid-5");
        assertEquals(2000L, live.lastTs());
        assertEquals("DRIVING", live.status());
    }

    private CanonicalTelemetryEvent createEvent(String pid, long ts, float speed, Boolean ign, String chargeState) {
        return new CanonicalTelemetryEvent(
            "evt-" + ts, pid, 1, "A", 1, ts, ts, null,
            13082700, 80270700, 90, speed, 100.0, ign,
            50.0f, 60.0f, null, chargeState, 0.0f, null, null,
            List.of(), null, 0
        );
    }
}
