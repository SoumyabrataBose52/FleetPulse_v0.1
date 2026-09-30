package com.fleetpulse.streamengine.geo;

/**
 * Pure spatial base32 geohash encoder (§7.4).
 */
public final class Geohash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private Geohash() {}

    public static String encode(double lat, double lon, int precision) {
        if (precision <= 0 || precision > 12) {
            precision = 7;
        }

        double minLat = -90.0, maxLat = 90.0;
        double minLon = -180.0, maxLon = 180.0;

        StringBuilder geohash = new StringBuilder(precision);
        boolean isEven = true;
        int bit = 0;
        int ch = 0;

        while (geohash.length() < precision) {
            if (isEven) {
                double mid = (minLon + maxLon) / 2.0;
                if (lon >= mid) {
                    ch |= (1 << (4 - bit));
                    minLon = mid;
                } else {
                    maxLon = mid;
                }
            } else {
                double mid = (minLat + maxLat) / 2.0;
                if (lat >= mid) {
                    ch |= (1 << (4 - bit));
                    minLat = mid;
                } else {
                    maxLat = mid;
                }
            }

            isEven = !isEven;
            if (bit < 4) {
                bit++;
            } else {
                geohash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }

        return geohash.toString();
    }
}
