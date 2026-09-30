package com.fleetpulse.orderer.dedupe;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-vehicle in-memory state for exact sequence and recent-id deduplication.
 */
public class VehicleDedupeState {

    private final Tier1SequenceWindow tier1 = new Tier1SequenceWindow(4096);
    private final Set<String> exactRecentIds = new LinkedHashSet<>();
    private final int maxExactIds;

    public VehicleDedupeState(int maxExactIds) {
        this.maxExactIds = maxExactIds;
    }

    public VehicleDedupeState() {
        this(2000);
    }

    public synchronized Tier1SequenceWindow getTier1() {
        return tier1;
    }

    public synchronized boolean containsExactId(String eventId) {
        return exactRecentIds.contains(eventId);
    }

    public synchronized void recordExactId(String eventId) {
        if (exactRecentIds.size() >= maxExactIds) {
            Iterator<String> it = exactRecentIds.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        exactRecentIds.add(eventId);
    }
}
