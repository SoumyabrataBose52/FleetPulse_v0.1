package com.fleetpulse.orderer.dedupe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Tier1SequenceWindowTest {

    private Tier1SequenceWindow window;

    @BeforeEach
    void setUp() {
        window = new Tier1SequenceWindow(4096);
    }

    @Test
    void testMonotonicSequenceIsNew() {
        for (long i = 1; i <= 100; i++) {
            assertEquals(Tier1SequenceWindow.Result.NEW, window.checkAndRecord(i));
        }
    }

    @Test
    void testDuplicateSequenceDetected() {
        assertEquals(Tier1SequenceWindow.Result.NEW, window.checkAndRecord(100));
        assertEquals(Tier1SequenceWindow.Result.NEW, window.checkAndRecord(101));
        assertEquals(Tier1SequenceWindow.Result.DUPLICATE, window.checkAndRecord(100));
        assertEquals(Tier1SequenceWindow.Result.DUPLICATE, window.checkAndRecord(101));
    }

    @Test
    void testOutOfWindowSequence() {
        window.checkAndRecord(10_000);
        // Sequence 10_000 - 5_000 = 5_000, which is outside 4,096 window
        assertEquals(Tier1SequenceWindow.Result.OUT_OF_WINDOW, window.checkAndRecord(5_000));
        // Sequence 10_000 - 100 = 9_900, which is inside window
        assertEquals(Tier1SequenceWindow.Result.NEW, window.checkAndRecord(9_900));
    }
}
