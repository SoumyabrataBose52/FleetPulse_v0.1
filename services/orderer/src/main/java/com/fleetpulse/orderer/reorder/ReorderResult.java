package com.fleetpulse.orderer.reorder;

import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;
import java.util.List;

/**
 * Result container for reorder buffer output.
 */
public record ReorderResult(
    List<CanonicalTelemetryEvent> cleanEvents,
    List<CanonicalTelemetryEvent> lateEvents
) {}
