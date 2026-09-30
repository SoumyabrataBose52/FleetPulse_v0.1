package com.fleetpulse.streamengine.processor;

import com.fleetpulse.streamengine.geo.Haversine;
import com.fleetpulse.streamengine.model.AlertEvent;
import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.model.GeofenceEvent;
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Geofence evaluation, breach detection, and tow-suspected anomaly processor (§7.17, §8.S2).
 * Hysteresis: 2 consecutive samples for state change.
 * Tow Rule: Ignition OFF and anchor displacement > 300m within 60s -> TOW_SUSPECTED alert.
 */
@Component
public class GeofenceProcessor {

    private static final Logger log = LoggerFactory.getLogger(GeofenceProcessor.class);

    public record GeofenceDef(
        int id,
        String name,
        String type, // "DEPOT", "ALLOWED_ZONE", "RESTRICTED"
        int centerLatE6,
        int centerLonE6,
        double radiusMeters
    ) {}

    private final List<GeofenceDef> activeGeofences = new CopyOnWriteArrayList<>();
    private final Map<String, VehicleGeofenceState> states = new ConcurrentHashMap<>();

    private final double towDisplacementThresholdM;
    private final long towWindowMs;
    private final Counter geofenceEventsTotal;
    private final Counter towAlertsTotal;

    public GeofenceProcessor(
        @Value("${fleetpulse.geofence.tow-displacement-m:300.0}") double towDisplacementThresholdM,
        @Value("${fleetpulse.geofence.tow-window-ms:60000}") long towWindowMs,
        MeterRegistry registry
    ) {
        this.towDisplacementThresholdM = towDisplacementThresholdM;
        this.towWindowMs = towWindowMs;
        this.geofenceEventsTotal = registry.counter("fleetpulse.stream.geofence.events.total");
        this.towAlertsTotal = registry.counter("fleetpulse.stream.tow.alerts.total");

        // Seed with sample geofences (Depot and Restricted zone)
        activeGeofences.add(new GeofenceDef(101, "Main Depot Central", "DEPOT", 37_774_929, -122_419_416, 500.0));
        activeGeofences.add(new GeofenceDef(901, "Restricted Industrial Port", "RESTRICTED", 37_800_000, -122_400_000, 400.0));
    }

    public static class VehicleGeofenceState {
        public Map<Integer, Boolean> insideStatus = new HashMap<>();
        public Map<Integer, Integer> consecutiveSamples = new HashMap<>();

        // Tow detection anchor
        public boolean parked = false;
        public long parkedTs = 0L;
        public int parkLatE6 = 0;
        public int parkLonE6 = 0;
        public long lastTowAlertTs = 0L;
    }

    public record GeofenceResult(
        Optional<GeofenceEvent> geofenceEvent,
        Optional<AlertEvent> alertEvent
    ) {}

    public GeofenceResult processEvent(CanonicalTelemetryEvent event) {
        if (event == null || event.vehiclePid() == null || event.latE6() == null || event.lonE6() == null) {
            return new GeofenceResult(Optional.empty(), Optional.empty());
        }

        VehicleGeofenceState s = states.computeIfAbsent(event.vehiclePid(), k -> new VehicleGeofenceState());
        long now = event.tsEvent();
        boolean ignition = Boolean.TRUE.equals(event.ignition());

        GeofenceEvent resultGeofenceEvent = null;
        AlertEvent resultAlertEvent = null;

        // 1. Tow rule evaluation (§7.17)
        if (!ignition) {
            if (!s.parked) {
                s.parked = true;
                s.parkedTs = now;
                s.parkLatE6 = event.latE6();
                s.parkLonE6 = event.lonE6();
            } else {
                double disp = Haversine.distanceMeters(
                    s.parkLatE6 / 1e6, s.parkLonE6 / 1e6,
                    event.latE6() / 1e6, event.lonE6() / 1e6
                );
                long parkedDurationMs = now - s.parkedTs;
                if (disp > towDisplacementThresholdM && parkedDurationMs <= towWindowMs && (now - s.lastTowAlertTs > 300_000)) {
                    s.lastTowAlertTs = now;
                    towAlertsTotal.increment();

                    String alertId = UUID.randomUUID().toString();
                    String alertKey = computeDeterministicHash(event.vehiclePid() + ":TOW:" + (now / 300_000));
                    resultAlertEvent = new AlertEvent(
                        alertId,
                        alertKey,
                        event.tenantId(),
                        event.vehiclePid(),
                        "TOW_SUSPECTED",
                        "CRITICAL",
                        now,
                        String.format(Locale.ROOT, "Vehicle %s towed while ignition OFF (displacement: %.0fm)",
                            event.vehiclePid(), disp),
                        Map.of("displacement_m", String.format(Locale.ROOT, "%.1f", disp)),
                        null
                    );
                    log.warn("TOW_SUSPECTED alert for vehicle {}: disp={:.1f}m", event.vehiclePid(), disp);
                }
            }
        } else {
            s.parked = false;
        }

        // 2. Geofence evaluation (ENTER, EXIT, BREACH)
        for (GeofenceDef geo : activeGeofences) {
            double distMeters = Haversine.distanceMeters(
                geo.centerLatE6() / 1e6, geo.centerLonE6() / 1e6,
                event.latE6() / 1e6, event.lonE6() / 1e6
            );

            boolean currentlyInside = distMeters <= geo.radiusMeters();
            boolean wasInside = s.insideStatus.getOrDefault(geo.id(), false);
            int samples = s.consecutiveSamples.getOrDefault(geo.id(), 0);

            if (currentlyInside == wasInside) {
                s.consecutiveSamples.put(geo.id(), 0);
            } else {
                samples++;
                s.consecutiveSamples.put(geo.id(), samples);

                // 2 consecutive samples confirm transition
                if (samples >= 2) {
                    s.insideStatus.put(geo.id(), currentlyInside);
                    s.consecutiveSamples.put(geo.id(), 0);

                    String transition;
                    if (currentlyInside) {
                        transition = "RESTRICTED".equalsIgnoreCase(geo.type()) ? "BREACH" : "ENTER";
                    } else {
                        transition = "EXIT";
                    }

                    String eventId = computeDeterministicHash(event.vehiclePid() + ":" + geo.id() + ":" + now + ":" + transition);
                    resultGeofenceEvent = new GeofenceEvent(
                        eventId,
                        event.vehiclePid(),
                        event.tenantId(),
                        geo.id(),
                        geo.type(),
                        transition,
                        now,
                        event.latE6(),
                        event.lonE6()
                    );
                    geofenceEventsTotal.increment();
                    log.info("Geofence {} {} for vehicle {} (zone: {})",
                        geo.name(), transition, event.vehiclePid(), geo.type());

                    // If RESTRICTED breach -> also generate immediate CRITICAL alert
                    if ("BREACH".equals(transition)) {
                        String alertId = UUID.randomUUID().toString();
                        String alertKey = computeDeterministicHash(event.vehiclePid() + ":GEOFENCE_BREACH:" + geo.id() + ":" + (now / 300_000));
                        resultAlertEvent = new AlertEvent(
                            alertId,
                            alertKey,
                            event.tenantId(),
                            event.vehiclePid(),
                            "GEOFENCE_BREACH",
                            "CRITICAL",
                            now,
                            String.format("Vehicle %s breached restricted geofence %s", event.vehiclePid(), geo.name()),
                            Map.of("geofence_id", String.valueOf(geo.id()), "geofence_name", geo.name()),
                            null
                        );
                    }
                }
            }
        }

        return new GeofenceResult(Optional.ofNullable(resultGeofenceEvent), Optional.ofNullable(resultAlertEvent));
    }

    public void addGeofence(GeofenceDef def) {
        activeGeofences.add(def);
    }

    public List<GeofenceDef> getActiveGeofences() {
        return Collections.unmodifiableList(activeGeofences);
    }

    private String computeDeterministicHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
