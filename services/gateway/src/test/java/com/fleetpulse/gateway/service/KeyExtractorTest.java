package com.fleetpulse.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeyExtractorTest {

    private KeyExtractor keyExtractor;

    @BeforeEach
    void setUp() {
        keyExtractor = new KeyExtractor();
    }

    @Test
    void testExtractKeyFromDracoPositionalString() {
        String payload = "D1|1HGCR2F83HA000001|1758795302120|1308270|8027070|642|182347|1|615|P0301";
        String key = keyExtractor.extractKey(payload);
        assertEquals("1HGCR2F83HA000001", key);
    }

    @Test
    void testExtractKeyFromAstraJson() {
        String payload = "{\"vehicleId\":\"1HGCR2F83HA000002\",\"timestamp\":\"2026-09-25T10:15:02Z\",\"speedKph\":64.2}";
        String key = keyExtractor.extractKey(payload);
        assertEquals("1HGCR2F83HA000002", key);
    }

    @Test
    void testExtractKeyFromBorealisJson() {
        String payload = "{\"vin\":\"1HGCR2F83HA000003\",\"speed_mph\":39.9,\"ign\":1}";
        String key = keyExtractor.extractKey(payload);
        assertEquals("1HGCR2F83HA000003", key);
    }

    @Test
    void testExtractKeyFromCetusJson() {
        String payload = "{\"deviceId\":\"1HGCR2F83HA000004\",\"tsMs\":1758795302120,\"signals\":[]}";
        String key = keyExtractor.extractKey(payload);
        assertEquals("1HGCR2F83HA000004", key);
    }

    @Test
    void testFallbackForUnparseablePayload() {
        String payload = "some random plain text";
        String key = keyExtractor.extractKey(payload);
        assertNotNull(key);
        assertFalse(key.isBlank());
    }
}
