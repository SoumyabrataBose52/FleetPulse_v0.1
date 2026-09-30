package com.fleetpulse.orderer.dedupe;

import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * §7.6 Two-Tier Deduplicator with Zero-Loss Rule.
 *
 * Tier 1: Per-vehicle sliding bitmap of last 4,096 sequence numbers (O(1)).
 * Tier 2: Rotating Bloom filter on event_id + confirming exact recent-id set.
 *
 * Zero-Loss Rule: Never drop on a Bloom positive alone. If the exact set says
 * "not present", accept the event as a Bloom false positive.
 */
@Component
public class Deduplicator {

    private final RotatingBloomFilter bloomFilter;
    private final Cache<String, VehicleDedupeState> vehicleStates;
    private final Counter droppedDuplicatesCounter;
    private final Counter bloomFpCounter;

    public Deduplicator(MeterRegistry registry) {
        this.bloomFilter = new RotatingBloomFilter(1_000_000, 0.001);
        this.vehicleStates = Caffeine.newBuilder()
            .maximumSize(150_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

        this.droppedDuplicatesCounter = Counter.builder("fp_orderer_dropped_duplicates_total")
            .description("Total duplicate telematics events dropped by orderer")
            .register(registry);

        this.bloomFpCounter = Counter.builder("fp_orderer_bloom_fp_total")
            .description("Total Bloom filter false positives confirmed by exact set")
            .register(registry);
    }

    public Deduplicator() {
        this(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    public boolean isDuplicate(CanonicalTelemetryEvent event) {
        String pid = event.vehiclePid();
        VehicleDedupeState state = vehicleStates.get(pid, k -> new VehicleDedupeState(2000));
        String eventId = event.eventId();

        // 1. Tier 1: Sequence number check (§7.6)
        if (event.seq() != null) {
            Tier1SequenceWindow.Result r = state.getTier1().checkAndRecord(event.seq());
            if (r == Tier1SequenceWindow.Result.DUPLICATE) {
                droppedDuplicatesCounter.increment();
                return true;
            } else if (r == Tier1SequenceWindow.Result.NEW) {
                bloomFilter.add(eventId);
                state.recordExactId(eventId);
                return false;
            }
            // OUT_OF_WINDOW falls through to Tier 2
        }

        // 2. Tier 2: Rotating Bloom filter check
        if (!bloomFilter.mightContain(eventId)) {
            // Definitely new
            bloomFilter.add(eventId);
            state.recordExactId(eventId);
            return false;
        }

        // 3. Bloom positive: confirm against exact recent-id set (Zero-Loss Rule §7.6)
        if (state.containsExactId(eventId)) {
            // Confirmed exact duplicate
            droppedDuplicatesCounter.increment();
            return true;
        }

        // Bloom false positive! Accept event under Zero-Loss Rule
        bloomFpCounter.increment();
        bloomFilter.add(eventId);
        state.recordExactId(eventId);
        return false;
    }
}
