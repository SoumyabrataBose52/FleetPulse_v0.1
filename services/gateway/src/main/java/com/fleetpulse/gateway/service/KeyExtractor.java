package com.fleetpulse.gateway.service;

import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * §4.3 Fast Syntactic Partition Key Extractor.
 *
 * Extracts the raw device id / VIN string from raw telematics payloads
 * with zero-allocation / minimal parsing overhead for Kafka partitioning.
 */
@Component
public class KeyExtractor {

    public String extractKey(String payload) {
        if (payload == null || payload.isBlank()) {
            return UUID.randomUUID().toString();
        }

        String trimmed = payload.trim();

        // 1. Pipe-delimited Draco format (§5.2): D1|<VIN>|...
        if (trimmed.startsWith("D1|") || trimmed.startsWith("D2|")) {
            int firstPipe = trimmed.indexOf('|');
            int secondPipe = trimmed.indexOf('|', firstPipe + 1);
            if (firstPipe != -1 && secondPipe > firstPipe + 1) {
                return trimmed.substring(firstPipe + 1, secondPipe).trim();
            }
        }

        // 2. Fast substring lookup for JSON keys: "vin", "vehicleId", "deviceId"
        String[] candidateKeys = {"\"vin\":", "\"vehicleId\":", "\"deviceId\":"};
        for (String candidate : candidateKeys) {
            int idx = trimmed.indexOf(candidate);
            if (idx != -1) {
                int valStart = trimmed.indexOf('\"', idx + candidate.length());
                if (valStart != -1) {
                    int valEnd = trimmed.indexOf('\"', valStart + 1);
                    if (valEnd != -1) {
                        return trimmed.substring(valStart + 1, valEnd).trim();
                    }
                }
            }
        }

        // Fallback: stable hash or random UUID
        return UUID.randomUUID().toString();
    }
}
