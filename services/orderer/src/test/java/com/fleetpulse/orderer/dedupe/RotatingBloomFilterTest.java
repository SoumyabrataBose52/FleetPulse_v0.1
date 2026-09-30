package com.fleetpulse.orderer.dedupe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RotatingBloomFilterTest {

    @Test
    void testBasicAddAndMightContain() {
        RotatingBloomFilter filter = new RotatingBloomFilter(10_000, 0.001);

        assertFalse(filter.mightContain("event-1"));
        filter.add("event-1");
        assertTrue(filter.mightContain("event-1"));
        assertFalse(filter.mightContain("event-2"));
    }

    @Test
    void testGenerationalRotation() {
        RotatingBloomFilter filter = new RotatingBloomFilter(10, 0.01);

        // Add 5 items
        for (int i = 0; i < 5; i++) {
            filter.add("item-" + i);
        }

        // Rotate: items should still be found in previousGeneration
        filter.rotate();
        for (int i = 0; i < 5; i++) {
            assertTrue(filter.mightContain("item-" + i));
        }

        // Rotate again: items are now expired
        filter.rotate();
        for (int i = 0; i < 5; i++) {
            assertFalse(filter.mightContain("item-" + i));
        }
    }
}
