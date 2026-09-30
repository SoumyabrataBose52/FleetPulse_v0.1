package com.fleetpulse.telemetrysink.sink;

import com.fleetpulse.telemetrysink.model.CanonicalTelemetryEvent;

import java.util.List;

/**
 * ClickHouse client interface for telemetry ingestion.
 */
public interface ClickHouseClient {

    /**
     * Inserts a batch of canonical telemetry events with an optional deduplication token.
     *
     * @param events             List of events to insert
     * @param deduplicationToken ClickHouse insert_deduplication_token (e.g. topic-partition-firstOffset-lastOffset)
     */
    void insertTelemetryBatch(List<CanonicalTelemetryEvent> events, String deduplicationToken);

    /**
     * Checks ClickHouse availability via ping/health endpoint.
     *
     * @return true if healthy, false otherwise
     */
    boolean ping();
}
