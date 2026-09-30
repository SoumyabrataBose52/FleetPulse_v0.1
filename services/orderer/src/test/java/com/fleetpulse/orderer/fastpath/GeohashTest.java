package com.fleetpulse.orderer.fastpath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeohashTest {

    @Test
    void testGeohashEncodeMatchesStandard() {
        String gh7 = Geohash.encode(13.0827, 80.2707, 7);
        assertEquals("tf346te", gh7);

        String gh6 = Geohash.encode(13.0827, 80.2707, 6);
        assertEquals("tf346t", gh6);
    }
}
