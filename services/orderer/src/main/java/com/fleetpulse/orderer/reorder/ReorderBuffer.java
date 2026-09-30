package com.fleetpulse.orderer.reorder;

import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * §7.7 Per-Vehicle Reorder Buffer with Watermark.
 *
 * Enforces monotonic event ordering within an allowed lateness watermark W = max_ts_seen - L.
 * Late events (ts_event <= last_emitted_ts) are marked with QUALITY_LATE and routed to telemetry.late.v1.
 */
@Component
public class ReorderBuffer {

    public static final int QUALITY_GPS_SUSPECT = 1;
    public static final int QUALITY_LATE = 2;
    public static final int QUALITY_IMPUTED = 4;
    public static final int QUALITY_VIN_WARN = 8;
    public static final int QUALITY_CLOCK_SKEW = 16;

    private final long watermarkLagMs;
    private final int maxBufferCapacity;
    private final Cache<String, VehicleReorderState> vehicleBuffers;
    private final Counter cleanCounter;
    private final Counter lateCounter;

    public ReorderBuffer(
        @Value("${orderer.watermark-lag-ms:10000}") long watermarkLagMs,
        @Value("${orderer.max-buffer-per-vehicle:256}") int maxBufferCapacity,
        MeterRegistry registry
    ) {
        this.watermarkLagMs = watermarkLagMs;
        this.maxBufferCapacity = maxBufferCapacity;
        this.vehicleBuffers = Caffeine.newBuilder()
            .maximumSize(150_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

        this.cleanCounter = Counter.builder("fp_orderer_clean_total")
            .description("Total ordered telematics events emitted to clean topic")
            .register(registry);

        this.lateCounter = Counter.builder("fp_orderer_late_total")
            .description("Total late telematics events routed to late topic")
            .register(registry);
    }

    public ReorderBuffer() {
        this(10_000, 256, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    public ReorderResult processEvent(CanonicalTelemetryEvent event) {
        String pid = event.vehiclePid();
        VehicleReorderState state = vehicleBuffers.get(pid, k -> new VehicleReorderState());

        List<CanonicalTelemetryEvent> clean = new ArrayList<>();
        List<CanonicalTelemetryEvent> late = new ArrayList<>();

        long now = System.currentTimeMillis();
        state.updateLastSeenWallTime(now);

        // Skew guard: timestamps > now + 5 min are flagged CLOCK_SKEW (§7.7)
        if (event.tsEvent() > now + 300_000L) {
            event = event.withQuality(event.quality() | QUALITY_CLOCK_SKEW);
        }

        synchronized (state) {
            // Check if event is late relative to last emitted event
            if (state.getLastEmittedTs() != -1 && event.tsEvent() <= state.getLastEmittedTs()) {
                // Event is late! (§7.7)
                CanonicalTelemetryEvent lateEvent = event.withQuality(event.quality() | QUALITY_LATE);
                late.add(lateEvent);
                lateCounter.increment();
                return new ReorderResult(clean, late);
            }

            // In-window event: update watermark and push to heap
            state.updateMaxTsSeen(event.tsEvent());
            state.getHeap().offer(event);

            long watermark = state.getMaxTsSeen() - watermarkLagMs;

            // Pop and emit while heap minimum is at or before watermark
            while (!state.getHeap().isEmpty() && state.getHeap().peek().tsEvent() <= watermark) {
                CanonicalTelemetryEvent popped = state.getHeap().poll();
                state.setLastEmittedTs(popped.tsEvent());
                clean.add(popped);
                cleanCounter.increment();
            }

            // Buffer cap guard (256): force-emit oldest to prevent unbounded memory (§7.7)
            while (state.getHeap().size() > maxBufferCapacity) {
                CanonicalTelemetryEvent forcePopped = state.getHeap().poll();
                state.setLastEmittedTs(forcePopped.tsEvent());
                clean.add(forcePopped);
                cleanCounter.increment();
            }
        }

        return new ReorderResult(clean, late);
    }

    public List<CanonicalTelemetryEvent> flushSilentVehicles(long wallNow) {
        List<CanonicalTelemetryEvent> flushed = new ArrayList<>();

        for (VehicleReorderState state : vehicleBuffers.asMap().values()) {
            synchronized (state) {
                if (wallNow - state.getLastSeenWallTime() > watermarkLagMs && !state.getHeap().isEmpty()) {
                    while (!state.getHeap().isEmpty()) {
                        CanonicalTelemetryEvent popped = state.getHeap().poll();
                        state.setLastEmittedTs(popped.tsEvent());
                        flushed.add(popped);
                        cleanCounter.increment();
                    }
                }
            }
        }

        return flushed;
    }

    public int getBufferedCount(String pid) {
        VehicleReorderState state = vehicleBuffers.getIfPresent(pid);
        return state != null ? state.getHeap().size() : 0;
    }
}
