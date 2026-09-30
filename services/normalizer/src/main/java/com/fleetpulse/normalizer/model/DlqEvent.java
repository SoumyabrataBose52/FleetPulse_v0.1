package com.fleetpulse.normalizer.model;

import java.util.Map;

/**
 * §4.5 & §15.6 Quarantined DLQ payload event structure.
 */
public record DlqEvent(
    String deviceId,
    String oem,
    DlqReason reasonCode,
    String errorMessage,
    String rawPayload,
    Map<String, String> headers,
    long timestamp
) {}
