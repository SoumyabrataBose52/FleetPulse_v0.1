package com.fleetpulse.orderer.dedupe;

import java.util.BitSet;

/**
 * §7.6 Tier 1 Sequence Window (4,096 items).
 *
 * Provides exact O(1) deduplication for telematics events containing sequence numbers.
 */
public class Tier1SequenceWindow {

    public enum Result {
        NEW,
        DUPLICATE,
        OUT_OF_WINDOW
    }

    private final int windowSize;
    private final BitSet bitSet;
    private long highestSeq = -1;

    public Tier1SequenceWindow(int windowSize) {
        this.windowSize = windowSize;
        this.bitSet = new BitSet(windowSize);
    }

    public Tier1SequenceWindow() {
        this(4096);
    }

    public synchronized Result checkAndRecord(long seq) {
        if (highestSeq == -1) {
            highestSeq = seq;
            bitSet.set((int) (seq % windowSize));
            return Result.NEW;
        }

        if (seq == highestSeq) {
            return Result.DUPLICATE;
        }

        if (seq > highestSeq) {
            long advance = seq - highestSeq;
            if (advance >= windowSize) {
                bitSet.clear();
            } else {
                for (long i = highestSeq + 1; i <= seq; i++) {
                    bitSet.clear((int) (i % windowSize));
                }
            }
            highestSeq = seq;
            bitSet.set((int) (seq % windowSize));
            return Result.NEW;
        }

        long diff = highestSeq - seq;
        if (diff >= windowSize) {
            return Result.OUT_OF_WINDOW;
        }

        int index = (int) (seq % windowSize);
        if (bitSet.get(index)) {
            return Result.DUPLICATE;
        }

        bitSet.set(index);
        return Result.NEW;
    }

    public synchronized long getHighestSeq() {
        return highestSeq;
    }
}
