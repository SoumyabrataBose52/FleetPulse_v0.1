package com.fleetpulse.streamengine.processor;

import com.fleetpulse.streamengine.geo.Geohash;
import com.fleetpulse.streamengine.geo.Haversine;
import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.model.TripEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streaming FSM for trip and stop segmentation (§7.10, §8.M2).
 * Hysteresis: Moving >= 5.0 km/h, Stopped < 1.0 km/h.
 * Anchor radius: 30m against GPS jitter (stationary vehicles produce zero trips).
 */
@Component
public class TripsProcessor {

    private static final Logger log = LoggerFactory.getLogger(TripsProcessor.class);

    private final double movingSpeedKmh;
    private final double stoppedSpeedKmh;
    private final double anchorRadiusM;
    private final double tripStartDisplacementM;
    private final int tripCloseStationaryS;
    private final double fuelPricePerL;
    private final double elecPricePerKwh;

    private final Map<String, VehicleTripState> states = new ConcurrentHashMap<>();
    private final Counter tripsCompletedTotal;

    public TripsProcessor(
        @Value("${fleetpulse.stream.thresholds.moving-speed-kmh:5.0}") double movingSpeedKmh,
        @Value("${fleetpulse.stream.thresholds.stopped-speed-kmh:1.0}") double stoppedSpeedKmh,
        @Value("${fleetpulse.stream.thresholds.anchor-radius-m:30.0}") double anchorRadiusM,
        @Value("${fleetpulse.stream.thresholds.trip-start-displacement-m:50.0}") double tripStartDisplacementM,
        @Value("${fleetpulse.stream.thresholds.trip-close-stationary-s:300}") int tripCloseStationaryS,
        @Value("${fleetpulse.stream.thresholds.fuel-price-per-l:1.45}") double fuelPricePerL,
        @Value("${fleetpulse.stream.thresholds.electricity-price-per-kwh:0.22}") double elecPricePerKwh,
        MeterRegistry registry
    ) {
        this.movingSpeedKmh = movingSpeedKmh;
        this.stoppedSpeedKmh = stoppedSpeedKmh;
        this.anchorRadiusM = anchorRadiusM;
        this.tripStartDisplacementM = tripStartDisplacementM;
        this.tripCloseStationaryS = tripCloseStationaryS;
        this.fuelPricePerL = fuelPricePerL;
        this.elecPricePerKwh = elecPricePerKwh;
        this.tripsCompletedTotal = registry.counter("fleetpulse.stream.trips.completed.total");
    }

    public enum FsmState {
        STOPPED,
        MOVING_CANDIDATE,
        IN_TRIP,
        STOP_CANDIDATE
    }

    public static class VehicleTripState {
        public FsmState state = FsmState.STOPPED;
        public long candidateStartTs = 0L;
        public long tripStartTs = 0L;
        public int startLatE6 = 0;
        public int startLonE6 = 0;
        public int lastLatE6 = 0;
        public int lastLonE6 = 0;
        public int anchorLatE6 = 0;
        public int anchorLonE6 = 0;
        public double distanceKm = 0.0;
        public Double lastOdoKm = null;
        public int idleS = 0;
        public long lastMovingTs = 0L;
        public long lastSampleTs = 0L;
        public double fuelConsumedL = 0.0;
        public double energyConsumedKwh = 0.0;
        public Float lastFuelPct = null;
        public Float lastSocPct = null;
    }

    public Optional<TripEvent> processEvent(CanonicalTelemetryEvent event) {
        if (event == null || event.vehiclePid() == null) {
            return Optional.empty();
        }

        VehicleTripState s = states.computeIfAbsent(event.vehiclePid(), k -> {
            VehicleTripState init = new VehicleTripState();
            if (event.latE6() != null && event.lonE6() != null) {
                init.anchorLatE6 = event.latE6();
                init.anchorLonE6 = event.lonE6();
                init.lastLatE6 = event.latE6();
                init.lastLonE6 = event.lonE6();
            }
            init.lastOdoKm = event.odoKm();
            init.lastSampleTs = event.tsEvent();
            return init;
        });

        float speed = event.speedKmh() != null ? event.speedKmh() : 0.0f;
        boolean ignition = Boolean.TRUE.equals(event.ignition());
        long now = event.tsEvent();

        // Calculate displacement from anchor
        double anchorDisp = 0.0;
        if (event.latE6() != null && event.lonE6() != null && s.anchorLatE6 != 0) {
            anchorDisp = Haversine.distanceMeters(
                s.anchorLatE6 / 1e6, s.anchorLonE6 / 1e6,
                event.latE6() / 1e6, event.lonE6() / 1e6
            );
        }

        TripEvent completedTrip = null;

        // FSM Transitions
        switch (s.state) {
            case STOPPED -> {
                // To start moving: speed >= movingSpeed and displacement > anchor radius
                if (speed >= movingSpeedKmh && anchorDisp >= anchorRadiusM) {
                    s.state = FsmState.MOVING_CANDIDATE;
                    s.candidateStartTs = now;
                } else if (event.latE6() != null && event.lonE6() != null && anchorDisp < anchorRadiusM) {
                    // Stationary GPS jitter: update anchor slowly to damp drift
                    s.anchorLatE6 = (int) (s.anchorLatE6 * 0.9 + event.latE6() * 0.1);
                    s.anchorLonE6 = (int) (s.anchorLonE6 * 0.9 + event.lonE6() * 0.1);
                }
            }
            case MOVING_CANDIDATE -> {
                if (speed < stoppedSpeedKmh) {
                    // False start: drop back to STOPPED
                    s.state = FsmState.STOPPED;
                } else {
                    long durationCandidateS = (now - s.candidateStartTs) / 1000;
                    if (durationCandidateS >= 10 || anchorDisp >= tripStartDisplacementM) {
                        // Confirmed Trip Start!
                        s.state = FsmState.IN_TRIP;
                        s.tripStartTs = s.candidateStartTs;
                        s.startLatE6 = event.latE6() != null ? event.latE6() : s.anchorLatE6;
                        s.startLonE6 = event.lonE6() != null ? event.lonE6() : s.anchorLonE6;
                        s.distanceKm = 0.0;
                        s.idleS = 0;
                        s.lastMovingTs = now;
                        s.fuelConsumedL = 0.0;
                        s.energyConsumedKwh = 0.0;
                        s.lastOdoKm = event.odoKm();
                        s.lastFuelPct = event.fuelPct();
                        s.lastSocPct = event.socPct();
                    }
                }
            }
            case IN_TRIP -> {
                // Accumulate distance
                accumulateDistance(s, event);

                if (speed < stoppedSpeedKmh) {
                    s.state = FsmState.STOP_CANDIDATE;
                    s.candidateStartTs = now;
                } else {
                    s.lastMovingTs = now;
                }

                // Check 15-min data gap while in trip
                if (now - s.lastSampleTs >= 15 * 60 * 1000) {
                    completedTrip = closeTrip(s, event, "TRUNCATED");
                }
            }
            case STOP_CANDIDATE -> {
                long stationaryS = (now - s.candidateStartTs) / 1000;
                s.idleS += (int) ((now - s.lastSampleTs) / 1000);

                if (speed >= movingSpeedKmh) {
                    // Resumed trip
                    s.state = FsmState.IN_TRIP;
                    s.lastMovingTs = now;
                    accumulateDistance(s, event);
                } else if (!ignition || stationaryS >= tripCloseStationaryS) {
                    // Confirmed trip completion!
                    completedTrip = closeTrip(s, event, "COMPLETED");
                }
            }
        }

        s.lastSampleTs = now;
        if (event.latE6() != null && event.lonE6() != null) {
            s.lastLatE6 = event.latE6();
            s.lastLonE6 = event.lonE6();
        }
        if (event.odoKm() != null) s.lastOdoKm = event.odoKm();

        return Optional.ofNullable(completedTrip);
    }

    private void accumulateDistance(VehicleTripState s, CanonicalTelemetryEvent event) {
        if (event.odoKm() != null && s.lastOdoKm != null) {
            double deltaOdo = event.odoKm() - s.lastOdoKm;
            if (deltaOdo > 0 && deltaOdo < 5.0) { // Reasonable 1s-10s delta
                s.distanceKm += deltaOdo;
                return;
            }
        }

        // Fallback haversine delta
        if (event.latE6() != null && event.lonE6() != null && s.lastLatE6 != 0) {
            double stepM = Haversine.distanceMeters(
                s.lastLatE6 / 1e6, s.lastLonE6 / 1e6,
                event.latE6() / 1e6, event.lonE6() / 1e6
            );
            if (stepM > 2.0 && stepM < 2000.0) { // Reject GPS teleport anomalies
                s.distanceKm += (stepM / 1000.0);
            }
        }
    }

    private TripEvent closeTrip(VehicleTripState s, CanonicalTelemetryEvent event, String status) {
        int durationS = (int) Math.max(1, (event.tsEvent() - s.tripStartTs) / 1000);
        String startGeohash = Geohash.encode(s.startLatE6 / 1e6, s.startLonE6 / 1e6, 7);
        String endGeohash = Geohash.encode(
            (event.latE6() != null ? event.latE6() : s.lastLatE6) / 1e6,
            (event.lonE6() != null ? event.lonE6() : s.lastLonE6) / 1e6, 7
        );

        // Cost estimation (§8.M5)
        double estimatedFuelL = s.distanceKm * 0.08; // ~8 L / 100km default
        double cost = estimatedFuelL * fuelPricePerL;
        double co2Kg = estimatedFuelL * 2.31;

        String tripId = computeDeterministicTripId(event.vehiclePid(), s.tripStartTs);

        TripEvent trip = new TripEvent(
            tripId,
            event.vehiclePid(),
            event.tenantId(),
            null,
            s.tripStartTs,
            event.tsEvent(),
            s.startLatE6,
            s.startLonE6,
            startGeohash,
            event.latE6() != null ? event.latE6() : s.lastLatE6,
            event.lonE6() != null ? event.lonE6() : s.lastLonE6,
            endGeohash,
            Math.round(s.distanceKm * 1000.0) / 1000.0,
            durationS,
            s.idleS,
            null,
            estimatedFuelL,
            Math.round(cost * 100.0) / 100.0,
            Math.round(co2Kg * 100.0) / 100.0,
            status
        );

        // Reset state to STOPPED and anchor at trip end
        s.state = FsmState.STOPPED;
        if (event.latE6() != null && event.lonE6() != null) {
            s.anchorLatE6 = event.latE6();
            s.anchorLonE6 = event.lonE6();
        }
        s.distanceKm = 0.0;
        s.idleS = 0;

        tripsCompletedTotal.increment();
        log.info("Closed trip {} for vehicle {}: {} km in {} s", tripId, event.vehiclePid(), trip.distanceKm(), durationS);
        return trip;
    }

    private String computeDeterministicTripId(String vehiclePid, long startTs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((vehiclePid + ":" + startTs).getBytes(StandardCharsets.UTF_8));
            UUID uuid = UUID.nameUUIDFromBytes(hash);
            return uuid.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
