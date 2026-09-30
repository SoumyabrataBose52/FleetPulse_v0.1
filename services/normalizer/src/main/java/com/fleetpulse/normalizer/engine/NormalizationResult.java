package com.fleetpulse.normalizer.engine;

import com.fleetpulse.normalizer.model.CanonicalTelemetryEvent;
import com.fleetpulse.normalizer.model.DlqEvent;

/**
 * Result container for telematics event normalization.
 */
public record NormalizationResult(
    boolean success,
    CanonicalTelemetryEvent canonicalEvent,
    DlqEvent dlqEvent
) {
    public static NormalizationResult success(CanonicalTelemetryEvent canonicalEvent) {
        return new NormalizationResult(true, canonicalEvent, null);
    }

    public static NormalizationResult dlq(DlqEvent dlqEvent) {
        return new NormalizationResult(false, null, dlqEvent);
    }
}
