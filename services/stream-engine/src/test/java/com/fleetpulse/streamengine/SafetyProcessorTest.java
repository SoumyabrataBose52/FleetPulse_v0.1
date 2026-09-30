package com.fleetpulse.streamengine;

import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.model.SafetyEvent;
import com.fleetpulse.streamengine.processor.SafetyProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SafetyProcessorTest {

    private SafetyProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SafetyProcessor(
            -3.5f,  // harshBrakeAccelMs2
            3.0f,   // harshAccelMs2
            100.0f, // defaultSpeedLimitKmh
            5000L,  // eventCooldownMs
            new SimpleMeterRegistry()
        );
    }

    private CanonicalTelemetryEvent makeEvent(String pid, long ts, float speedKmh, int headingDeg) {
        return new CanonicalTelemetryEvent(
            "evt-" + ts,
            pid,
            1,
            "FORD",
            1,
            ts,
            ts + 10,
            1L,
            37_774_929,
            -122_419_416,
            headingDeg,
            speedKmh,
            1000.0,
            true,
            75.0f,
            null,
            12.6f,
            null,
            null,
            85.0f,
            2000,
            List.of(),
            null,
            0
        );
    }

    @Test
    @DisplayName("Detects HARSH_BRAKE event when deceleration <= -3.5 m/s^2 (§7.16, §8.S1)")
    void testDetectsHarshBrake() {
        String pid = "veh-driver-01";
        long t = 1_700_000_000_000L;

        // Sample 1: 60 km/h
        processor.processEvent(makeEvent(pid, t, 60.0f, 90));

        // Sample 2 (1s later): drops to 20 km/h -> delta = -40 km/h in 1s = -11.1 m/s^2
        t += 1000L;
        Optional<SafetyEvent> event = processor.processEvent(makeEvent(pid, t, 20.0f, 90));

        assertTrue(event.isPresent(), "Harsh brake must be detected");
        assertEquals("HARSH_BRAKE", event.get().eventType());
        assertEquals(3.0f, event.get().weight());
        assertTrue(event.get().accelMs2() < -3.5f);
    }

    @Test
    @DisplayName("Detects HARSH_ACCEL event when acceleration >= 3.0 m/s^2 (§7.16, §8.S1)")
    void testDetectsHarshAccel() {
        String pid = "veh-driver-02";
        long t = 1_700_000_000_000L;

        // Sample 1: 0 km/h
        processor.processEvent(makeEvent(pid, t, 0.0f, 90));

        // Sample 2 (1s later): surges to 35 km/h -> delta = +35 km/h in 1s = +9.7 m/s^2
        t += 1000L;
        Optional<SafetyEvent> event = processor.processEvent(makeEvent(pid, t, 35.0f, 90));

        assertTrue(event.isPresent(), "Harsh acceleration must be detected");
        assertEquals("HARSH_ACCEL", event.get().eventType());
        assertEquals(2.0f, event.get().weight());
        assertTrue(event.get().accelMs2() > 3.0f);
    }

    @Test
    @DisplayName("Normal smooth driving produces zero harsh events")
    void testSmoothDrivingProducesNoEvents() {
        String pid = "veh-driver-smooth";
        long t = 1_700_000_000_000L;

        // Cruising at 50 km/h with subtle +- 1 km/h fluctuations
        for (int i = 0; i < 20; i++) {
            t += 1000L;
            float speed = 50.0f + (i % 2 == 0 ? 0.5f : -0.5f);
            Optional<SafetyEvent> event = processor.processEvent(makeEvent(pid, t, speed, 90));
            assertTrue(event.isEmpty(), "Smooth driving must not trigger any safety event");
        }
    }
}
