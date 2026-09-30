package com.fleetpulse.streamengine.processor;

import com.fleetpulse.streamengine.model.AlertEvent;
import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.model.ChargingSessionEvent;
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
 * EV charging session tracker, SoH estimator, and range-risk detector (§7.13, §7.15, §8.M4).
 */
@Component
public class EvProcessor {

    private static final Logger log = LoggerFactory.getLogger(EvProcessor.class);

    private final double elecPricePerKwh;
    private final double offPeakElecPricePerKwh;
    private final double nominalBatteryKwh;
    private final double chargerEfficiency;
    private final double minSocDeltaForSoh;
    private final double lowSocThresholdPct;
    private final double criticalSocThresholdPct;
    private final long alertCooldownMs;

    private final Map<String, VehicleEvState> states = new ConcurrentHashMap<>();
    private final Counter chargingSessionsCompletedTotal;
    private final Counter rangeRiskAlertsTotal;

    public EvProcessor(
        @Value("${fleetpulse.stream.thresholds.electricity-price-per-kwh:0.22}") double elecPricePerKwh,
        @Value("${fleetpulse.stream.thresholds.offpeak-electricity-price-per-kwh:0.12}") double offPeakElecPricePerKwh,
        @Value("${fleetpulse.stream.thresholds.nominal-battery-kwh:60.0}") double nominalBatteryKwh,
        @Value("${fleetpulse.stream.thresholds.charger-efficiency:0.92}") double chargerEfficiency,
        @Value("${fleetpulse.stream.thresholds.min-soc-delta-soh:15.0}") double minSocDeltaForSoh,
        @Value("${fleetpulse.stream.thresholds.low-soc-threshold-pct:15.0}") double lowSocThresholdPct,
        @Value("${fleetpulse.stream.thresholds.critical-soc-threshold-pct:8.0}") double criticalSocThresholdPct,
        @Value("${fleetpulse.stream.thresholds.alert-cooldown-ms:300000}") long alertCooldownMs,
        MeterRegistry registry
    ) {
        this.elecPricePerKwh = elecPricePerKwh;
        this.offPeakElecPricePerKwh = offPeakElecPricePerKwh;
        this.nominalBatteryKwh = nominalBatteryKwh;
        this.chargerEfficiency = chargerEfficiency;
        this.minSocDeltaForSoh = minSocDeltaForSoh;
        this.lowSocThresholdPct = lowSocThresholdPct;
        this.criticalSocThresholdPct = criticalSocThresholdPct;
        this.alertCooldownMs = alertCooldownMs;
        this.chargingSessionsCompletedTotal = registry.counter("fleetpulse.stream.ev.charging.sessions.total");
        this.rangeRiskAlertsTotal = registry.counter("fleetpulse.stream.ev.range_risk.alerts.total");
    }

    public static class VehicleEvState {
        public boolean isCharging = false;
        public long sessionStartTs = 0L;
        public float startSocPct = 0.0f;
        public float endSocPct = 0.0f;
        public double totalEnergyKwh = 0.0;
        public float peakPowerKw = 0.0f;
        public long lastTs = 0L;
        public Float currentSoh = null;
        public long lastRangeAlertTs = 0L;
    }

    public record EvResult(
        Optional<ChargingSessionEvent> chargingSession,
        Optional<AlertEvent> rangeRiskAlert
    ) {}

    public EvResult processEvent(CanonicalTelemetryEvent event) {
        if (event == null || event.vehiclePid() == null) {
            return new EvResult(Optional.empty(), Optional.empty());
        }

        Optional<ChargingSessionEvent> session = processChargingSession(event);
        Optional<AlertEvent> alert = processRangeRisk(event);
        return new EvResult(session, alert);
    }

    public Optional<ChargingSessionEvent> processChargingSession(CanonicalTelemetryEvent event) {
        if (event == null || event.vehiclePid() == null) {
            return Optional.empty();
        }

        VehicleEvState s = states.computeIfAbsent(event.vehiclePid(), k -> new VehicleEvState());
        long now = event.tsEvent();
        boolean chargingNow = "CHARGING".equalsIgnoreCase(event.chargeState())
            || (event.chargeKw() != null && event.chargeKw() > 0.05f);

        ChargingSessionEvent completedSession = null;

        if (chargingNow) {
            float kw = event.chargeKw() != null ? event.chargeKw() : 7.2f;
            float soc = event.socPct() != null ? event.socPct() : 0.0f;

            if (!s.isCharging) {
                // Plug-in initiated
                s.isCharging = true;
                s.sessionStartTs = now;
                s.startSocPct = soc;
                s.endSocPct = soc;
                s.totalEnergyKwh = 0.0;
                s.peakPowerKw = kw;
                s.lastTs = now;
            } else {
                // Ongoing charging session, integrate power: dE = P * dt
                long dtMs = now - s.lastTs;
                if (dtMs > 0 && dtMs < 300_000) {
                    double dtHours = dtMs / 3_600_000.0;
                    s.totalEnergyKwh += kw * dtHours;
                    s.peakPowerKw = Math.max(s.peakPowerKw, kw);
                }
                s.lastTs = now;
                s.endSocPct = soc;
            }
        } else {
            if (s.isCharging) {
                // Plug-out detected!
                completedSession = closeChargingSession(s, event);
                s.isCharging = false;
            }
        }

        return Optional.ofNullable(completedSession);
    }

    private ChargingSessionEvent closeChargingSession(VehicleEvState s, CanonicalTelemetryEvent event) {
        long endTs = s.lastTs > s.sessionStartTs ? s.lastTs : event.tsEvent();
        long durationMs = endTs - s.sessionStartTs;
        if (durationMs < 30_000) { // Glitch filter (< 30s)
            return null;
        }

        double durationH = Math.max(0.01, durationMs / 3_600_000.0);
        float avgPowerKw = (float) (s.totalEnergyKwh / durationH);

        double baselineCost = s.totalEnergyKwh * elecPricePerKwh;
        double smartCost = s.totalEnergyKwh * offPeakElecPricePerKwh;
        double costSaving = Math.max(0.0, baselineCost - smartCost);

        // Battery SoH estimation (§7.15)
        Float sohEstimate = null;
        float deltaSoc = s.endSocPct - s.startSocPct;
        if (deltaSoc >= minSocDeltaForSoh && s.totalEnergyKwh > 0.5) {
            double eBatt = s.totalEnergyKwh * chargerEfficiency;
            double calculatedSoh = (eBatt / ((deltaSoc / 100.0) * nominalBatteryKwh)) * 100.0;
            sohEstimate = (float) Math.min(100.0, Math.max(50.0, calculatedSoh));
            s.currentSoh = sohEstimate;
        }

        String sessionId = computeDeterministicSessionId(event.vehiclePid(), s.sessionStartTs);

        ChargingSessionEvent session = new ChargingSessionEvent(
            sessionId,
            event.vehiclePid(),
            event.tenantId(),
            null, // chargerId
            null, // depotId
            s.sessionStartTs,
            endTs,
            Math.round(s.startSocPct * 10.0f) / 10.0f,
            Math.round(s.endSocPct * 10.0f) / 10.0f,
            Math.round(s.totalEnergyKwh * 1000.0) / 1000.0,
            Math.round(avgPowerKw * 10.0f) / 10.0f,
            Math.round(s.peakPowerKw * 10.0f) / 10.0f,
            Math.round(baselineCost * 100.0) / 100.0,
            Math.round(smartCost * 100.0) / 100.0,
            Math.round(costSaving * 100.0) / 100.0,
            sohEstimate != null ? Math.round(sohEstimate * 10.0f) / 10.0f : null
        );

        chargingSessionsCompletedTotal.increment();
        log.info("Closed charging session {} for vehicle {}: {} kWh, saving: ${}, SoH: {}",
            sessionId, event.vehiclePid(), session.energyKwh(), session.costSaving(), session.sohEstimate());
        return session;
    }

    public Optional<AlertEvent> processRangeRisk(CanonicalTelemetryEvent event) {
        if (event == null || event.vehiclePid() == null || event.socPct() == null) {
            return Optional.empty();
        }

        VehicleEvState s = states.computeIfAbsent(event.vehiclePid(), k -> new VehicleEvState());
        long now = event.tsEvent();

        // Check alert cooldown
        if (now - s.lastRangeAlertTs < alertCooldownMs) {
            return Optional.empty();
        }

        float soc = event.socPct();
        float soh = s.currentSoh != null ? s.currentSoh : 95.0f;
        double usableKwh = (soc / 100.0) * nominalBatteryKwh * (soh / 100.0) - (0.10 * nominalBatteryKwh);

        if (soc <= lowSocThresholdPct || usableKwh < 4.0) {
            s.lastRangeAlertTs = now;
            rangeRiskAlertsTotal.increment();

            String severity = soc <= criticalSocThresholdPct ? "CRITICAL" : "HIGH";
            long windowStart = now - (now % alertCooldownMs);
            String alertKey = computeDeterministicAlertKey(event.vehiclePid(), "RANGE_RISK", windowStart);
            String alertId = UUID.randomUUID().toString();

            Map<String, String> details = new HashMap<>();
            details.put("soc_pct", String.format(Locale.ROOT, "%.1f", soc));
            details.put("usable_kwh", String.format(Locale.ROOT, "%.2f", Math.max(0.0, usableKwh)));
            details.put("soh_est", String.format(Locale.ROOT, "%.1f", soh));

            AlertEvent alert = new AlertEvent(
                alertId,
                alertKey,
                event.tenantId(),
                event.vehiclePid(),
                "RANGE_RISK",
                severity,
                now,
                String.format(Locale.ROOT, "Vehicle %s low usable energy: %.1f kWh (SoC: %.1f%%)",
                    event.vehiclePid(), Math.max(0.0, usableKwh), soc),
                details,
                null
            );

            log.warn("Generated RANGE_RISK alert for vehicle {}: severity={}, soc={}%",
                event.vehiclePid(), severity, soc);
            return Optional.of(alert);
        }

        return Optional.empty();
    }

    private String computeDeterministicSessionId(String vehiclePid, long startTs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((vehiclePid + ":charge:" + startTs).getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private String computeDeterministicAlertKey(String vehiclePid, String type, long windowStart) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((vehiclePid + ":" + type + ":" + windowStart).getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
