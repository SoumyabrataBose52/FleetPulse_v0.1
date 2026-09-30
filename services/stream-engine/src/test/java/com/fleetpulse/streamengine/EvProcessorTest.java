package com.fleetpulse.streamengine;

import com.fleetpulse.streamengine.model.AlertEvent;
import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.model.ChargingSessionEvent;
import com.fleetpulse.streamengine.processor.EvProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EvProcessorTest {

    private EvProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new EvProcessor(
            0.22,    // electricityPricePerKwh = $0.22
            0.12,    // offPeakElectricityPricePerKwh = $0.12
            60.0,    // nominalBatteryKwh = 60.0
            0.92,    // chargerEfficiency = 0.92
            15.0,    // minSocDeltaForSoh = 15.0%
            15.0,    // lowSocThresholdPct = 15.0%
            8.0,     // criticalSocThresholdPct = 8.0%
            300_000, // alertCooldownMs = 5 minutes
            new SimpleMeterRegistry()
        );
    }

    private CanonicalTelemetryEvent makeEvEvent(
        String pid,
        long ts,
        String chargeState,
        Float chargeKw,
        Float socPct
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
            37_774_929,
            -122_419_416,
            0,
            0.0f,
            2500.0,
            false,
            null,
            socPct,
            380.0f,
            chargeState,
            chargeKw,
            25.0f,
            0,
            List.of(),
            null,
            0
        );
    }

    @Test
    @DisplayName("EV charging session tracks energy, counterfactual savings and battery SoH (§7.13, §7.15, §8.M4)")
    void testChargingSessionLifecycleAndCostSavings() {
        String pid = "veh-ev-01";
        long t = 1_700_000_000_000L;

        // 1. Plug-in at 20.0% SoC, charging at 11.0 kW AC
        processor.processChargingSession(makeEvEvent(pid, t, "CHARGING", 11.0f, 20.0f));

        // 2. Charge for 3600 seconds (1 hour) -> should deliver 11.0 kWh, SoC reaches 38.0% (delta = 18%)
        for (int i = 1; i <= 60; i++) {
            t += 60_000L; // 1-minute steps
            float currentSoc = 20.0f + (18.0f * (i / 60.0f));
            Optional<ChargingSessionEvent> inProgress = processor.processChargingSession(
                makeEvEvent(pid, t, "CHARGING", 11.0f, currentSoc)
            );
            assertTrue(inProgress.isEmpty(), "Session should not close while charging");
        }

        // 3. Plug-out (charge state transitions to DISCONNECTED)
        t += 1000L;
        Optional<ChargingSessionEvent> completed = processor.processChargingSession(
            makeEvEvent(pid, t, "DISCONNECTED", 0.0f, 38.0f)
        );

        assertTrue(completed.isPresent(), "Session must complete on plug-out");
        ChargingSessionEvent session = completed.get();
        assertEquals(pid, session.vehiclePid());
        assertEquals(1, session.tenantId());
        assertEquals(20.0f, session.startSocPct(), 0.1);
        assertEquals(38.0f, session.endSocPct(), 0.1);
        assertTrue(session.energyKwh() >= 10.5 && session.energyKwh() <= 11.5, "Energy should be ~11.0 kWh");
        assertTrue(session.baselineCost() > session.smartCost(), "Baseline cost must exceed smart cost");
        assertTrue(session.costSaving() > 0.5, "Smart charging cost saving must be positive");
        assertNotNull(session.sohEstimate(), "SoH must be estimated when delta SoC >= 15%");
        assertTrue(session.sohEstimate() >= 50.0f && session.sohEstimate() <= 100.0f, "SoH in reasonable bound");
    }

    @Test
    @DisplayName("Range risk alert fires on low SoC and respects cooldown period (§8.M4)")
    void testRangeRiskAlertAndCooldown() {
        String pid = "veh-ev-low-soc";
        long t = 1_700_000_000_000L;

        // Normal SoC (50%) -> no alert
        Optional<AlertEvent> alert50 = processor.processRangeRisk(makeEvEvent(pid, t, "DISCONNECTED", 0.0f, 50.0f));
        assertTrue(alert50.isEmpty());

        // Low SoC (12%) -> triggers HIGH alert
        t += 10_000L;
        Optional<AlertEvent> alert12 = processor.processRangeRisk(makeEvEvent(pid, t, "DISCONNECTED", 0.0f, 12.0f));
        assertTrue(alert12.isPresent());
        assertEquals("RANGE_RISK", alert12.get().type());
        assertEquals("HIGH", alert12.get().severity());
        assertNotNull(alert12.get().alertKey());

        // Repeated low SoC 10s later -> suppressed by cooldown
        t += 10_000L;
        Optional<AlertEvent> alertCooldown = processor.processRangeRisk(makeEvEvent(pid, t, "DISCONNECTED", 0.0f, 11.0f));
        assertTrue(alertCooldown.isEmpty(), "Alert within cooldown period must be suppressed");

        // After cooldown expires (300s) with critical SoC (7%) -> CRITICAL alert fires
        t += 301_000L;
        Optional<AlertEvent> alertCritical = processor.processRangeRisk(makeEvEvent(pid, t, "DISCONNECTED", 0.0f, 7.0f));
        assertTrue(alertCritical.isPresent(), "Alert must fire after cooldown expiry");
        assertEquals("CRITICAL", alertCritical.get().severity());
    }
}
