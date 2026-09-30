package com.fleetpulse.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/**
 * §4.4 Gateway runtime configuration properties.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
    KafkaProps kafka,
    QueueProps queue,
    int maxBatchSize,
    int maxPayloadBytes,
    MtlsProps mtls
) {
    public record KafkaProps(String rawTopic) {}

    public record QueueProps(
        int capacity,
        double highWatermarkRatio
    ) {
        public int getHighWatermarkLimit() {
            return (int) (capacity * highWatermarkRatio);
        }
    }

    public record MtlsProps(
        boolean enabled,
        List<String> allowedCns
    ) {}
}
