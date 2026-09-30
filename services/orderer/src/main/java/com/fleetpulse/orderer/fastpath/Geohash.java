package com.fleetpulse.orderer.fastpath;

/**
 * §7.4 Pure Geohash Encoder for spatial indexing.
 */
public class Geohash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    public static String encode(double latitude, double longitude, int precision) {
        double[] latInterval = {-90.0, 90.0};
        double[] lonInterval = {-180.0, 180.0};

        StringBuilder geohash = new StringBuilder(precision);
        boolean isEven = true;
        int bit = 0;
        int ch = 0;

        while (geohash.length() < precision) {
            double mid;
            if (isEven) {
                mid = (lonInterval[0] + lonInterval[1]) / 2.0;
                if (longitude > mid) {
                    ch |= (1 << (4 - bit));
                    lonInterval[0] = mid;
                } else {
                    lonInterval[1] = mid;
                }
            } else {
                mid = (latInterval[0] + latInterval[1]) / 2.0;
                if (latitude > mid) {
                    ch |= (1 << (4 - bit));
                    latInterval[0] = mid;
                } else {
                    latInterval[1] = mid;
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
