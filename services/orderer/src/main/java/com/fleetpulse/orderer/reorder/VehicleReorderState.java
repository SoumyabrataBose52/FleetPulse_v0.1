package com.fleetpulse.orderer.reorder;

import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Per-vehicle in-memory min-heap buffer ordered by event timestamp and sequence.
 */
public class VehicleReorderState {

    private static final Comparator<CanonicalTelemetryEvent> COMPARATOR = (a, b) -> {
        int cmp = Long.compare(a.tsEvent(), b.tsEvent());
        if (cmp != 0) return cmp;
        if (a.seq() != null && b.seq() != null) {
            return Long.compare(a.seq(), b.seq());
        }
        return 0;
    };

    private final PriorityQueue<CanonicalTelemetryEvent> heap = new PriorityQueue<>(COMPARATOR);
    private long maxTsSeen = -1;
    private long lastEmittedTs = -1;
    private long lastSeenWallTime = System.currentTimeMillis();

    public synchronized PriorityQueue<CanonicalTelemetryEvent> getHeap() {
        return heap;
    }

    public synchronized long getMaxTsSeen() {
        return maxTsSeen;
    }

    public synchronized void updateMaxTsSeen(long ts) {
        if (ts > maxTsSeen) {
            maxTsSeen = ts;
        }
    }

    public synchronized long getLastEmittedTs() {
        return lastEmittedTs;
    }

    public synchronized void setLastEmittedTs(long ts) {
        this.lastEmittedTs = Math.max(this.lastEmittedTs, ts);
    }

    public synchronized long getLastSeenWallTime() {
        return lastSeenWallTime;
    }

    public synchronized void updateLastSeenWallTime(long wallTime) {
        this.lastSeenWallTime = wallTime;
    }
}
