package com.fleetpulse.streamengine.processor;

import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.model.SafetyEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Driver safety harsh event detector and scoring engine (§7.16, §8.S1).
 * Recomputes events from physical speed deltas:
 * - HARSH_BRAKE (a <= -3.5 m/s^2, weight 3)
 * - HARSH_ACCEL (a >= 3.0 m/s^2, weight 2)
 * - HARSH_CORNER (heading turn >= 30 deg at speed >= 30 km/h, weight 2)
 * - OVERSPEED (speed > speed_limit + 15 km/h)
 */
@Component
public class SafetyProcessor {

    private static final Logger log = LoggerFactory.getLogger(SafetyProcessor.class);

    private final float harshBrakeAccelMs2;
    private final float harshAccelMs2;
    private final float defaultSpeedLimitKmh;
    private final long eventCooldownMs;

    private final Map<String, VehicleSafetyState> states = new ConcurrentHashMap<>();
    private final Counter safetyEventsTotal;

    public SafetyProcessor(
        @Value("${fleetpulse.safety.harsh-brake-ms2:-3.5}") float harshBrakeAccelMs2,
        @Value("${fleetpulse.safety.harsh-accel-ms2:3.0}") float harshAccelMs2,
        @Value("${fleetpulse.safety.default-speed-limit-kmh:100.0}") float defaultSpeedLimitKmh,
        @Value("${fleetpulse.safety.event-cooldown-ms:5000}") long eventCooldownMs,
        MeterRegistry registry
    ) {
        this.harshBrakeAccelMs2 = harshBrakeAccelMs2;
        this.harshAccelMs2 = harshAccelMs2;
        this.defaultSpeedLimitKmh = defaultSpeedLimitKmh;
        this.eventCooldownMs = eventCooldownMs;
        this.safetyEventsTotal = registry.counter("fleetpulse.stream.safety.events.total");
    }

    public static class VehicleSafetyState {
        public long lastTs = 0L;
        public float lastSpeedKmh = 0.0f;
        public int lastHeadingDeg = 0;
        public long lastEventTs = 0L;
    }

    public Optional<SafetyEvent> processEvent(CanonicalTelemetryEvent event) {
        if (event == null || event.vehiclePid() == null || event.speedKmh() == null) {
            return Optional.empty();
        }

        VehicleSafetyState s = states.computeIfAbsent(event.vehiclePid(), k -> {
            VehicleSafetyState init = new VehicleSafetyState();
            init.lastTs = event.tsEvent();
            init.lastSpeedKmh = event.speedKmh();
            init.lastHeadingDeg = event.headingDeg() != null ? event.headingDeg() : 0;
            return init;
        });

        long now = event.tsEvent();
        long dtMs = now - s.lastTs;

        // Valid physical sample interval for delta calculation: 200ms to 5000ms
        if (dtMs < 200 || dtMs > 5000) {
            s.lastTs = now;
            s.lastSpeedKmh = event.speedKmh();
            if (event.headingDeg() != null) s.lastHeadingDeg = event.headingDeg();
            return Optional.empty();
        }

        float curSpeed = event.speedKmh();
        double dtS = dtMs / 1000.0;
        float deltaVMs = (float) ((curSpeed - s.lastSpeedKmh) / 3.6);
        float accelMs2 = (float) (deltaVMs / dtS);

        SafetyEvent detectedEvent = null;

        // Check cooldown to avoid multi-sampling single maneuver
        boolean canEmit = (now - s.lastEventTs) >= eventCooldownMs;

        if (canEmit) {
            if (accelMs2 <= harshBrakeAccelMs2) {
                // 1. HARSH BRAKE
                detectedEvent = createSafetyEvent(event, "HARSH_BRAKE", Math.abs(accelMs2), accelMs2, 3.0f);
            } else if (accelMs2 >= harshAccelMs2) {
                // 2. HARSH ACCEL
                detectedEvent = createSafetyEvent(event, "HARSH_ACCEL", accelMs2, accelMs2, 2.0f);
            } else if (curSpeed >= 30.0f && event.headingDeg() != null) {
                // 3. HARSH CORNER
                int hdgDiff = Math.abs(event.headingDeg() - s.lastHeadingDeg);
                if (hdgDiff > 180) hdgDiff = 360 - hdgDiff;
                float turnRateDegS = (float) (hdgDiff / dtS);
                if (turnRateDegS >= 30.0f) {
                    detectedEvent = createSafetyEvent(event, "HARSH_CORNER", turnRateDegS, accelMs2, 2.0f);
                }
            } else if (curSpeed > (defaultSpeedLimitKmh + 15.0f)) {
                // 4. OVERSPEED
                float overKmh = curSpeed - defaultSpeedLimitKmh;
                float weight = 1.0f + (overKmh / 10.0f);
                detectedEvent = createSafetyEvent(event, "OVERSPEED", overKmh, accelMs2, weight);
            }
        }

        s.lastTs = now;
        s.lastSpeedKmh = curSpeed;
        if (event.headingDeg() != null) s.lastHeadingDeg = event.headingDeg();

        if (detectedEvent != null) {
            s.lastEventTs = now;
            safetyEventsTotal.increment();
            log.info("Detected {} for vehicle {}: accel={:.2f} m/s^2, speed={:.1f} km/h",
                detectedEvent.eventType(), event.vehiclePid(), detectedEvent.accelMs2(), detectedEvent.speedKmh());
        }

        return Optional.ofNullable(detectedEvent);
    }

    private SafetyEvent createSafetyEvent(
        CanonicalTelemetryEvent event,
        String type,
        float severity,
        float accelMs2,
        float weight
    ) {
        String eventId = computeDeterministicEventId(event.vehiclePid(), event.tsEvent(), type);
        int lat = event.latE6() != null ? event.latE6() : 0;
        int lon = event.lonE6() != null ? event.lonE6() : 0;

        return new SafetyEvent(
            eventId,
            event.vehiclePid(),
            event.tenantId(),
            null,
            event.tsEvent(),
            type,
            severity,
            lat,
            lon,
            event.speedKmh() != null ? event.speedKmh() : 0.0f,
            defaultSpeedLimitKmh,
            accelMs2,
            weight
        );
    }

    private String computeDeterministicEventId(String vehiclePid, long ts, String type) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((vehiclePid + ":" + ts + ":" + type).getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
