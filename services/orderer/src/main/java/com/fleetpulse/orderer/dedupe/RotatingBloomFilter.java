package com.fleetpulse.orderer.dedupe;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * §7.5 & §7.6 Rotating Bloom Filter with double hashing.
 */
public class RotatingBloomFilter {

    private final int bitSize;
    private final int numHashes;
    private final int capacityPerGeneration;

    private BitSet currentGeneration;
    private BitSet previousGeneration;
    private final AtomicInteger currentCount = new AtomicInteger(0);

    public RotatingBloomFilter(int capacityPerGeneration, double falsePositiveRate) {
        this.capacityPerGeneration = capacityPerGeneration;
        // m = - (n * ln p) / (ln 2)^2
        this.bitSize = (int) Math.ceil(- (capacityPerGeneration * Math.log(falsePositiveRate)) / Math.pow(Math.log(2), 2));
        // k = (m / n) * ln 2
        this.numHashes = Math.max(1, (int) Math.round(((double) bitSize / capacityPerGeneration) * Math.log(2)));

        this.currentGeneration = new BitSet(bitSize);
        this.previousGeneration = new BitSet(bitSize);
    }

    public RotatingBloomFilter() {
        this(500_000, 0.001); // 500K events per gen at 0.1% FP
    }

    public synchronized boolean mightContain(String item) {
        int[] hashes = computeHashes(item);
        if (matches(currentGeneration, hashes)) {
            return true;
        }
        return matches(previousGeneration, hashes);
    }

    public synchronized void add(String item) {
        if (currentCount.incrementAndGet() > capacityPerGeneration) {
            rotate();
        }
        int[] hashes = computeHashes(item);
        for (int h : hashes) {
            currentGeneration.set(h);
        }
    }

    public synchronized void rotate() {
        previousGeneration = currentGeneration;
        currentGeneration = new BitSet(bitSize);
        currentCount.set(0);
    }

    private boolean matches(BitSet bitSet, int[] hashes) {
        for (int h : hashes) {
            if (!bitSet.get(h)) {
                return false;
            }
        }
        return true;
    }

    private int[] computeHashes(String item) {
        byte[] bytes = item.getBytes(StandardCharsets.UTF_8);
        long hash64 = murmur64(bytes);
        int h1 = (int) hash64;
        int h2 = (int) (hash64 >>> 32);

        int[] result = new int[numHashes];
        for (int i = 0; i < numHashes; i++) {
            long combined = (long) h1 + (long) i * h2;
            int bit = (int) ((combined & 0x7fffffffffffffffL) % bitSize);
            result[i] = bit;
        }
        return result;
    }

    private long murmur64(byte[] data) {
        long h = 0x5bd1e9955bd1e995L ^ data.length;
        for (byte b : data) {
            h ^= b;
            h *= 0x5bd1e9955bd1e995L;
            h ^= (h >>> 47);
        }
        return h;
    }

    public int getBitSize() {
        return bitSize;
    }

    public int getNumHashes() {
        return numHashes;
    }
}
