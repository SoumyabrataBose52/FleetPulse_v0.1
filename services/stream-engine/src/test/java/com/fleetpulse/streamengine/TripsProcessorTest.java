package com.fleetpulse.streamengine;

import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.model.TripEvent;
import com.fleetpulse.streamengine.processor.TripsProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TripsProcessorTest {

    private TripsProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TripsProcessor(
            5.0,   // movingSpeedKmh
            1.0,   // stoppedSpeedKmh
            30.0,  // anchorRadiusM
            50.0,  // tripStartDisplacementM
            300,   // tripCloseStationaryS (5 minutes)
            1.45,  // fuelPricePerL
            0.22,  // elecPricePerKwh
            new SimpleMeterRegistry()
        );
    }

    private CanonicalTelemetryEvent makeEvent(
        String pid,
        long ts,
        int latE6,
        int lonE6,
        Float speedKmh,
        Double odoKm,
        Boolean ignition
    ) {
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
            90,
            speedKmh,
            odoKm,
            ignition,
            null,
            null,
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
    @DisplayName("Zero trips from stationary GPS jitter within 30m anchor radius (§8.M2 acceptance)")
    void testStationaryGpsJitterProducesZeroTrips() {
        String pid = "veh-stationary-01";
        long startTs = 1_700_000_000_000L;
        int baseLat = 37_774_929;
        int baseLon = -122_419_416;

        // 100 samples simulating parked vehicle with jitter ~ 5 to 15 meters
        for (int i = 0; i < 100; i++) {
            long ts = startTs + (i * 1000L);
            int jitterLat = baseLat + (i % 5 - 2) * 10; // small drift
            int jitterLon = baseLon + (i % 7 - 3) * 10;
            float jitterSpeed = (float) (Math.random() * 2.0); // 0-2 km/h GPS drift speed (< 5 km/h)

            Optional<TripEvent> trip = processor.processEvent(
                makeEvent(pid, ts, jitterLat, jitterLon, jitterSpeed, 1000.0, true)
            );
            assertTrue(trip.isEmpty(), "Stationary jitter must not trigger a trip event");
        }
    }

    @Test
    @DisplayName("Valid trip lifecycle: Start -> Drive -> Stop -> Complete TripEvent")
    void testValidTripLifecycle() {
        String pid = "veh-commute-01";
        long t = 1_700_000_000_000L;
        int startLat = 37_774_929;
        int startLon = -122_419_416;

        // 1. Initial stopped sample
        processor.processEvent(makeEvent(pid, t, startLat, startLon, 0.0f, 5000.0, true));

        // 2. Start driving: speed 35 km/h, moving north significantly (> 50m displacement)
        Optional<TripEvent> eventDuringDrive = Optional.empty();
        for (int i = 1; i <= 60; i++) {
            t += 1000L;
            int curLat = startLat + (i * 50); // moving ~5.5m per sec
            double curOdo = 5000.0 + (i * 0.01);
            eventDuringDrive = processor.processEvent(makeEvent(pid, t, curLat, startLon, 35.0f, curOdo, true));
            assertTrue(eventDuringDrive.isEmpty(), "Trip must remain in progress while driving");
        }

        // 3. Vehicle stops at destination (< 1.0 km/h)
        int destLat = startLat + (60 * 50);
        double finalOdo = 5000.0 + (60 * 0.01); // 0.6 km distance

        // Dwell at destination for 299s with engine idling (should not close yet)
        for (int i = 0; i < 299; i++) {
            t += 1000L;
            Optional<TripEvent> premature = processor.processEvent(
                makeEvent(pid, t, destLat, startLon, 0.0f, finalOdo, true)
            );
            assertTrue(premature.isEmpty(), "Trip should not close before 300s dwell threshold");
        }

        // Dwell reaches 301s -> Trip must close!
        t += 2000L;
        Optional<TripEvent> completedTrip = processor.processEvent(
            makeEvent(pid, t, destLat, startLon, 0.0f, finalOdo, true)
        );

        assertTrue(completedTrip.isPresent(), "Trip must be completed after 300s stationary dwell");
        TripEvent trip = completedTrip.get();
        assertEquals(pid, trip.vehiclePid());
        assertEquals(1, trip.tenantId());
        assertTrue(trip.distanceKm() >= 0.45, "Trip distance should be ~0.48 km (accounting for candidate confirmation)");
        assertEquals("COMPLETED", trip.status());
        assertNotNull(trip.tripId());
        assertTrue(trip.startGeohash7().length() == 7);
        assertTrue(trip.endGeohash7().length() == 7);
    }
}
