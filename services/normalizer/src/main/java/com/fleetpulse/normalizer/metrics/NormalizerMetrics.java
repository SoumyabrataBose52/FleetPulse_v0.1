package com.fleetpulse.normalizer.metrics;

import com.fleetpulse.normalizer.model.DlqReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * §15.6 Normalizer Prometheus Metrics.
 */
@Component
public class NormalizerMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> eventCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<DlqReason, Counter> dlqCounters = new ConcurrentHashMap<>();
    private final Timer normalizationTimer;

    public NormalizerMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.normalizationTimer = Timer.builder("fp_normalizer_duration_seconds")
            .description("Normalizer processing latency in seconds")
            .publishPercentileHistogram()
            .register(registry);
    }

    public void recordEvent(String status, String oem) {
        String safeOem = oem != null ? oem : "UNKNOWN";
        String key = status + ":" + safeOem;
        eventCounters.computeIfAbsent(key, k ->
            Counter.builder("fp_normalizer_events_total")
                .description("Total telematics events processed by normalizer")
                .tag("status", status)
                .tag("oem", safeOem)
                .register(registry)
        ).increment();
    }

    public void recordDlq(DlqReason reason) {
        dlqCounters.computeIfAbsent(reason, r ->
            Counter.builder("fp_normalizer_dlq_total")
                .description("Total events sent to DLQ by reason code")
                .tag("reason", r.name())
                .register(registry)
        ).increment();
    }

    public Timer getTimer() {
        return normalizationTimer;
    }
}
