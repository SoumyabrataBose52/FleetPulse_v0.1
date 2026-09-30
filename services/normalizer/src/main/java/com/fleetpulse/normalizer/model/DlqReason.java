package com.fleetpulse.normalizer.model;

/**
 * §4.5 & §15.6 Dead Letter Queue (DLQ) reason codes.
 */
public enum DlqReason {
    MALFORMED,
    UNKNOWN_OEM,
    MAPPING_ERROR,
    INVALID_VIN,
    UNKNOWN_VEHICLE,
    SCHEMA_VIOLATION
}
