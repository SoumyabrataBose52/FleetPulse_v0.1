package com.fleetpulse.streamengine;

import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.model.IdleEvent;
import com.fleetpulse.streamengine.processor.IdleProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IdleProcessorTest {

    private IdleProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new IdleProcessor(
            120,   // minIdleS = 120 (2 mins)
            5,     // allowedIdleMin = 5 mins
            1.5,   // idleFuelBurnLph = 1.5 L/h
            1.45,  // fuelPricePerL = $1.45/L
            new SimpleMeterRegistry()
        );
    }

    private CanonicalTelemetryEvent makeEvent(String pid, long ts, float speedKmh, boolean ignition, Integer rpm) {
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
            0,
            speedKmh,
            1200.0,
            ignition,
            80.0f,
            null,
            12.6f,
            null,
            null,
            85.0f,
            rpm,
            List.of(),
            null,
            0
        );
    }

    @Test
    @DisplayName("Traffic light stop (45s < 120s) produces zero idle episodes (§8.M3 acceptance)")
    void testTrafficLightStopProducesNoIdleEpisode() {
        String pid = "veh-commuter-02";
        long t = 1_700_000_000_000L;

        // Vehicle stopped at red traffic light for 45 seconds (engine running at 700 RPM)
        for (int i = 0; i < 45; i++) {
            t += 1000L;
            Optional<IdleEvent> idle = processor.processEvent(makeEvent(pid, t, 0.0f, true, 700));
            assertTrue(idle.isEmpty(), "Idle episode must not trigger during traffic light wait");
        }

        // Green light: vehicle drives away at 40 km/h
        t += 1000L;
        Optional<IdleEvent> idleAfterDrive = processor.processEvent(makeEvent(pid, t, 40.0f, true, 2200));
        assertTrue(idleAfterDrive.isEmpty(), "Traffic light stop under 120s must NOT produce an idle episode");
    }

    @Test
    @DisplayName("Extended idle (600s = 10 mins) produces confirmed IdleEvent with avoidable cost")
    void testExtendedIdleProducesConfirmedIdleEvent() {
        String pid = "veh-delivery-03";
        long t = 1_700_000_000_000L;

        // Idling for 600s (10 minutes)
        for (int i = 0; i <= 600; i++) {
            t += 1000L;
            Optional<IdleEvent> idle = processor.processEvent(makeEvent(pid, t, 0.0f, true, 800));
            assertTrue(idle.isEmpty(), "Idle event closes only when idle state transitions");
        }

        // Vehicle starts moving -> closes idle episode
        t += 1000L;
        Optional<IdleEvent> completedIdle = processor.processEvent(makeEvent(pid, t, 25.0f, true, 2000));
        assertTrue(completedIdle.isPresent(), "Extended idle must produce confirmed IdleEvent");

        IdleEvent idle = completedIdle.get();
        assertEquals(pid, idle.vehiclePid());
        assertTrue(idle.durationS() >= 600);
        assertTrue(idle.fuelBurnedL() > 0.2, "Fuel burn should be ~0.25 L");
        assertTrue(idle.cost() > 0.3, "Cost should be ~$0.36");
        // 10 mins idle - 5 mins allowed = 5 mins avoidable
        assertTrue(idle.avoidableCost() > 0.1, "Avoidable cost should be positive for > 5 min allowance");
        assertNotNull(idle.geohash7());
    }
}
