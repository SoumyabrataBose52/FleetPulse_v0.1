package com.fleetpulse.orderer.dedupe;

import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeduplicatorTest {

    private Deduplicator deduplicator;

    @BeforeEach
    void setUp() {
        deduplicator = new Deduplicator();
    }

    @Test
    void testTier1SequenceDeduplication() {
        CanonicalTelemetryEvent e1 = createEvent("evt-1", "pid-1", 1000L, 100L);
        CanonicalTelemetryEvent e2 = createEvent("evt-2", "pid-1", 1001L, 100L); // Duplicate seq
        CanonicalTelemetryEvent e3 = createEvent("evt-3", "pid-1", 1002L, 101L); // New seq

        assertFalse(deduplicator.isDuplicate(e1));
        assertTrue(deduplicator.isDuplicate(e2)); // Dropped as duplicate
        assertFalse(deduplicator.isDuplicate(e3));
    }

    @Test
    void testTier2ExactIdDeduplicationWithoutSeq() {
        CanonicalTelemetryEvent e1 = createEvent("evt-unique-1", "pid-1", 1000L, null);
        CanonicalTelemetryEvent e2 = createEvent("evt-unique-1", "pid-1", 1000L, null); // Exact duplicate
        CanonicalTelemetryEvent e3 = createEvent("evt-unique-2", "pid-1", 1001L, null);

        assertFalse(deduplicator.isDuplicate(e1));
        assertTrue(deduplicator.isDuplicate(e2)); // Dropped as duplicate
        assertFalse(deduplicator.isDuplicate(e3));
    }

    @Test
    void testDifferentVehiclesDoNotCollide() {
        CanonicalTelemetryEvent e1 = createEvent("evt-1", "pid-A", 1000L, 50L);
        CanonicalTelemetryEvent e2 = createEvent("evt-2", "pid-B", 1000L, 50L);

        assertFalse(deduplicator.isDuplicate(e1));
        assertFalse(deduplicator.isDuplicate(e2));
    }

    private CanonicalTelemetryEvent createEvent(String eventId, String pid, long ts, Long seq) {
        return new CanonicalTelemetryEvent(
            eventId, pid, 1, "A", 1, ts, ts, seq,
            13082700, 80270700, 90, 60.0f, 100.0, true,
            50.0f, 60.0f, null, null, null, null, null,
            List.of(), null, 0
        );
    }
}
