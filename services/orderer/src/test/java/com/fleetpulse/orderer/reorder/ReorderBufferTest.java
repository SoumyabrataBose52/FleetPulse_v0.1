package com.fleetpulse.orderer.reorder;

import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReorderBufferTest {

    private ReorderBuffer buffer;

    @BeforeEach
    void setUp() {
        // Watermark lag 5,000 ms, max capacity 10
        buffer = new ReorderBuffer(5000L, 10, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void testOutOfOrderEventsAreEmittedInOrder() {
        String pid = "test-pid-1";

        // Ingest events out of order: 1000, 4000, 2000, 3000
        buffer.processEvent(createEvent(pid, 1000L));
        buffer.processEvent(createEvent(pid, 4000L));
        buffer.processEvent(createEvent(pid, 2000L));
        buffer.processEvent(createEvent(pid, 3000L));

        // Advance watermark by sending event at 10,000 ms -> Watermark = 10,000 - 5,000 = 5,000 ms
        ReorderResult result = buffer.processEvent(createEvent(pid, 10_000L));

        List<CanonicalTelemetryEvent> clean = result.cleanEvents();
        assertFalse(clean.isEmpty());

        // Events 1000, 2000, 3000, 4000 should be emitted in exact ascending order!
        assertEquals(4, clean.size());
        assertEquals(1000L, clean.get(0).tsEvent());
        assertEquals(2000L, clean.get(1).tsEvent());
        assertEquals(3000L, clean.get(2).tsEvent());
        assertEquals(4000L, clean.get(3).tsEvent());
    }

    @Test
    void testLateArrivalRoutedToLateEventsWithQualityFlag() {
        String pid = "test-pid-2";

        // Emit up to 5,000 ms
        buffer.processEvent(createEvent(pid, 5000L));
        // Event at 12,000 advances watermark to 7,000 ms -> 5,000 is emitted
        ReorderResult r1 = buffer.processEvent(createEvent(pid, 12_000L));
        assertEquals(1, r1.cleanEvents().size());
        assertEquals(5000L, r1.cleanEvents().get(0).tsEvent());

        // Now an event arrives with ts = 4,000 ms (older than last emitted ts 5,000 ms)
        ReorderResult r2 = buffer.processEvent(createEvent(pid, 4000L));

        assertTrue(r2.cleanEvents().isEmpty());
        assertEquals(1, r2.lateEvents().size());
        CanonicalTelemetryEvent late = r2.lateEvents().get(0);
        assertEquals(4000L, late.tsEvent());
        // QUALITY_LATE bit (2) must be set
        assertEquals(ReorderBuffer.QUALITY_LATE, late.quality() & ReorderBuffer.QUALITY_LATE);
    }

    @Test
    void testBufferOverflowForceEmitsOldest() {
        String pid = "test-pid-3";

        // Buffer capacity is 10. Send 15 events all within watermark window
        List<CanonicalTelemetryEvent> forceEmitted = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            ReorderResult res = buffer.processEvent(createEvent(pid, 1000L + i));
            forceEmitted.addAll(res.cleanEvents());
        }

        // Must have force-emitted the overflow (15 - 10 = 5 events)
        assertFalse(forceEmitted.isEmpty());
        assertEquals(5, forceEmitted.size());
    }

    @Test
    void testSilentVehicleFlush() {
        String pid = "test-pid-4";

        buffer.processEvent(createEvent(pid, 2000L));
        assertEquals(1, buffer.getBufferedCount(pid));

        // Flush after silent interval
        long futureWall = System.currentTimeMillis() + 10_000L;
        List<CanonicalTelemetryEvent> flushed = buffer.flushSilentVehicles(futureWall);

        assertEquals(1, flushed.size());
        assertEquals(2000L, flushed.get(0).tsEvent());
        assertEquals(0, buffer.getBufferedCount(pid));
    }

    private CanonicalTelemetryEvent createEvent(String pid, long ts) {
        return new CanonicalTelemetryEvent(
            "evt-" + ts, pid, 1, "A", 1, ts, ts, null,
            13082700, 80270700, 90, 60.0f, 100.0, true,
            50.0f, 60.0f, null, null, null, null, null,
            List.of(), null, 0
        );
    }
}
