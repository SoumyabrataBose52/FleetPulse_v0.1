package com.fleetpulse.normalizer.model;

/**
 * §4.3 & §5.3 Vehicle inventory identity metadata.
 */
public record VehicleMetadata(
    String vehiclePid,
    int tenantId,
    String vin,
    Integer modelId,
    Integer fleetId
) {}
