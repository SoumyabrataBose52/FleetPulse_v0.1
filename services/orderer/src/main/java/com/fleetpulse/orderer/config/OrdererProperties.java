package com.fleetpulse.orderer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * §15.7 Orderer runtime configuration properties.
 */
@ConfigurationProperties(prefix = "orderer")
public record OrdererProperties(
    KafkaProps kafka,
    long watermarkLagMs,
    int maxBufferPerVehicle,
    int seqWindowSize,
    long silentFlushIntervalMs,
    boolean fastpathEnabled
) {
    public record KafkaProps(
        String canonicalTopic,
        String cleanTopic,
        String lateTopic,
        String alertsTopic
    ) {}
}
