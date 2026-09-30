package com.fleetpulse.streamengine.processor;

import com.fleetpulse.streamengine.geo.Geohash;
import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.model.IdleEvent;
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
 * Idle detection and cost calculation processor (§7.11, §8.M3).
 * Minimum idle confirmation threshold: 120s (traffic lights produce no idle episodes).
 * Avoidable cost: max(0, idle_min - allowed_idle_min) * cost/min.
 */
@Component
public class IdleProcessor {

    private static final Logger log = LoggerFactory.getLogger(IdleProcessor.class);

    private final int minIdleS;
    private final int allowedIdleMin;
    private final double idleFuelBurnLph;
    private final double fuelPricePerL;

    private final Map<String, VehicleIdleState> states = new ConcurrentHashMap<>();
    private final Counter idleEpisodesTotal;

    public IdleProcessor(
        @Value("${fleetpulse.stream.thresholds.min-idle-s:120}") int minIdleS,
        @Value("${fleetpulse.stream.thresholds.allowed-idle-min:10}") int allowedIdleMin,
        @Value("${fleetpulse.stream.thresholds.idle-fuel-burn-lph:1.5}") double idleFuelBurnLph,
        @Value("${fleetpulse.stream.thresholds.fuel-price-per-l:1.45}") double fuelPricePerL,
        MeterRegistry registry
    ) {
        this.minIdleS = minIdleS;
        this.allowedIdleMin = allowedIdleMin;
        this.idleFuelBurnLph = idleFuelBurnLph;
        this.fuelPricePerL = fuelPricePerL;
        this.idleEpisodesTotal = registry.counter("fleetpulse.stream.idle.episodes.total");
    }

    public static class VehicleIdleState {
        public boolean isCandidate = false;
        public long candidateStartTs = 0L;
        public int latE6 = 0;
        public int lonE6 = 0;
        public boolean confirmed = false;
        public long lastTs = 0L;
    }

    public Optional<IdleEvent> processEvent(CanonicalTelemetryEvent event) {
        if (event == null || event.vehiclePid() == null) {
            return Optional.empty();
        }

        VehicleIdleState s = states.computeIfAbsent(event.vehiclePid(), k -> new VehicleIdleState());

        float speed = event.speedKmh() != null ? event.speedKmh() : 0.0f;
        boolean engineOn = (event.rpm() != null && event.rpm() > 0) || Boolean.TRUE.equals(event.ignition());
        boolean isCharging = "CHARGING".equals(event.chargeState());
        long now = event.tsEvent();

        IdleEvent completedEpisode = null;

        // Condition for idling: engine is ON, not charging, speed < 2.0 km/h
        boolean currentlyIdling = engineOn && !isCharging && (speed < 2.0f);

        if (currentlyIdling) {
            if (!s.isCandidate) {
                // Open idle candidate
                s.isCandidate = true;
                s.candidateStartTs = now;
                s.confirmed = false;
                if (event.latE6() != null && event.lonE6() != null) {
                    s.latE6 = event.latE6();
                    s.lonE6 = event.lonE6();
                }
            } else {
                long durationS = (now - s.candidateStartTs) / 1000;
                if (durationS >= minIdleS) {
                    s.confirmed = true;
                }
            }
        } else {
            // Speed >= 5.0 km/h, engine off, or charging
            if (s.isCandidate) {
                if (s.confirmed) {
                    // Close confirmed idle episode!
                    completedEpisode = closeIdleEpisode(s, event);
                }
                // Reset candidate
                s.isCandidate = false;
                s.confirmed = false;
            }
        }

        s.lastTs = now;
        return Optional.ofNullable(completedEpisode);
    }

    private IdleEvent closeIdleEpisode(VehicleIdleState s, CanonicalTelemetryEvent event) {
        int durationS = (int) Math.max(minIdleS, (event.tsEvent() - s.candidateStartTs) / 1000);
        double durationH = durationS / 3600.0;

        double fuelBurnL = durationH * idleFuelBurnLph;
        double cost = fuelBurnL * fuelPricePerL;
        double co2Kg = fuelBurnL * 2.31; // 2.31 kg CO2 / L fuel

        // Avoidable cost calculation (§7.11): max(0, idle_min - allowed_idle_min) * cost_per_min
        double idleMin = durationS / 60.0;
        double avoidableMin = Math.max(0.0, idleMin - allowedIdleMin);
        double costPerMin = (idleFuelBurnLph / 60.0) * fuelPricePerL;
        double avoidableCost = avoidableMin * costPerMin;

        String geohash7 = Geohash.encode(s.latE6 / 1e6, s.lonE6 / 1e6, 7);
        String idleId = computeDeterministicIdleId(event.vehiclePid(), s.candidateStartTs);

        IdleEvent idle = new IdleEvent(
            idleId,
            event.vehiclePid(),
            event.tenantId(),
            null,
            s.candidateStartTs,
            event.tsEvent(),
            durationS,
            s.latE6,
            s.lonE6,
            geohash7,
            Math.round(fuelBurnL * 1000.0) / 1000.0,
            Math.round(cost * 100.0) / 100.0,
            Math.round(avoidableCost * 100.0) / 100.0,
            Math.round(co2Kg * 100.0) / 100.0
        );

        idleEpisodesTotal.increment();
        log.info("Recorded idle episode {} for vehicle {}: {} s (avoidable cost: ${})",
            idleId, event.vehiclePid(), durationS, idle.avoidableCost());
        return idle;
    }

    private String computeDeterministicIdleId(String vehiclePid, long startTs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((vehiclePid + ":idle:" + startTs).getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
