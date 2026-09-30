package com.fleetpulse.orderer.reorder;

import com.fleetpulse.orderer.dedupe.Deduplicator;
import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §15.7 Orderer and Deduplicator Throughput Benchmark.
 */
class OrdererThroughputBenchmarkTest {

    private Deduplicator deduplicator;
    private ReorderBuffer reorderBuffer;

    @BeforeEach
    void setUp() {
        deduplicator = new Deduplicator();
        reorderBuffer = new ReorderBuffer(10_000L, 256, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void testOrdererPipelineThroughput() {
        int warmup = 10_000;
        int iterations = 50_000;

        // Warmup
        for (int i = 0; i < warmup; i++) {
            CanonicalTelemetryEvent event = createEvent("warmup-" + (i % 100), i, i);
            if (!deduplicator.isDuplicate(event)) {
                reorderBuffer.processEvent(event);
            }
        }

        // Timed run
        long start = System.nanoTime();
        int cleanCount = 0;
        for (int i = 0; i < iterations; i++) {
            CanonicalTelemetryEvent event = createEvent("veh-" + (i % 500), 10_000 + i, 10_000 + i);
            if (!deduplicator.isDuplicate(event)) {
                ReorderResult res = reorderBuffer.processEvent(event);
                cleanCount += res.cleanEvents().size();
            }
        }
        long durationNs = System.nanoTime() - start;
        double durationSec = durationNs / 1_000_000_000.0;
        double eventsPerSec = iterations / durationSec;

        System.out.printf("Orderer Benchmark: %d events in %.3f s = %.1f events/s/core%n",
            iterations, durationSec, eventsPerSec);

        assertTrue(eventsPerSec > 50_000.0, "Expected > 50K events/s/core, got: " + eventsPerSec);
    }

    private CanonicalTelemetryEvent createEvent(String pid, long ts, long seq) {
        return new CanonicalTelemetryEvent(
            "evt-" + pid + "-" + ts, pid, 1, "A", 1, ts, ts, seq,
            13082700, 80270700, 90, 60.0f, 100.0, true,
            50.0f, 60.0f, null, null, null, null, null,
            List.of(), null, 0
        );
    }
}
