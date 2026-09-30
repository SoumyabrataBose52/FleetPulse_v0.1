package com.fleetpulse.normalizer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * §4.5 & §15.6 Normalizer runtime configuration properties.
 */
@ConfigurationProperties(prefix = "normalizer")
public record NormalizerProperties(
    KafkaProps kafka,
    String mappingsDir,
    String seedVehiclesPath,
    boolean strictVin,
    CacheProps cache
) {
    public record KafkaProps(
        String rawTopic,
        String canonicalTopic,
        String dlqTopic,
        String mappingsTopic
    ) {}

    public record CacheProps(
        int maxSize,
        int expireAfterWriteMinutes
    ) {}
}
