package com.fleetpulse.telemetrysink.sink;

import com.fleetpulse.telemetrysink.model.CanonicalTelemetryEvent;

import java.util.List;

/**
 * High-performance formatter converting CanonicalTelemetryEvent batches to ClickHouse TabSeparated (TSV) format.
 */
public final class TelemetryBatchFormatter {

    private static final String NULL_VALUE = "\\N";
    private static final char TAB = '\t';
    private static final char LF = '\n';

    public static final String INSERT_QUERY =
        "INSERT INTO %s.%s (" +
        "tenant_id, vehicle_pid, ts, event_id, seq, " +
        "lat_e6, lon_e6, heading_deg, speed_kmh, odo_km, " +
        "ignition, fuel_pct, soc_pct, batt_voltage_v, charge_state, " +
        "charge_kw, coolant_c, rpm, dtc, evt, " +
        "quality, ingest_ts" +
        ") FORMAT TabSeparated";

    private TelemetryBatchFormatter() {
    }

    public static String toTabSeparated(List<CanonicalTelemetryEvent> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }

        // Preallocate ~128 bytes per event
        StringBuilder sb = new StringBuilder(events.size() * 128);

        for (CanonicalTelemetryEvent event : events) {
            appendEventTsv(sb, event);
        }

        return sb.toString();
    }

    private static void appendEventTsv(StringBuilder sb, CanonicalTelemetryEvent e) {
        // 1. tenant_id (UInt32)
        sb.append(e.tenantId()).append(TAB);

        // 2. vehicle_pid (UUID)
        sb.append(e.vehiclePid()).append(TAB);

        // 3. ts (DateTime64(3, 'UTC'))
        sb.append(e.getFormattedTs()).append(TAB);

        // 4. event_id (UInt64)
        sb.append(Long.toUnsignedString(e.getEventIdAsUInt64())).append(TAB);

        // 5. seq (Nullable(UInt64))
        if (e.seq() != null) sb.append(e.seq()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 6. lat_e6 (Nullable(Int32))
        if (e.latE6() != null) sb.append(e.latE6()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 7. lon_e6 (Nullable(Int32))
        if (e.lonE6() != null) sb.append(e.lonE6()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 8. heading_deg (Nullable(UInt16))
        if (e.headingDeg() != null) sb.append(e.headingDeg()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 9. speed_kmh (Nullable(Float32))
        if (e.speedKmh() != null) sb.append(e.speedKmh()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 10. odo_km (Nullable(Float64))
        if (e.odoKm() != null) sb.append(e.odoKm()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 11. ignition (Nullable(UInt8))
        if (e.ignition() != null) sb.append(e.ignition() ? '1' : '0'); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 12. fuel_pct (Nullable(Float32))
        if (e.fuelPct() != null) sb.append(e.fuelPct()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 13. soc_pct (Nullable(Float32))
        if (e.socPct() != null) sb.append(e.socPct()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 14. batt_voltage_v (Nullable(Float32))
        if (e.battVoltageV() != null) sb.append(e.battVoltageV()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 15. charge_state (LowCardinality(Nullable(String)))
        if (e.chargeState() != null) escapeString(sb, e.chargeState()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 16. charge_kw (Nullable(Float32))
        if (e.chargeKw() != null) sb.append(e.chargeKw()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 17. coolant_c (Nullable(Float32))
        if (e.coolantC() != null) sb.append(e.coolantC()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 18. rpm (Nullable(UInt32))
        if (e.rpm() != null) sb.append(e.rpm()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 19. dtc (Array(LowCardinality(String)))
        appendStringArray(sb, e.dtc());
        sb.append(TAB);

        // 20. evt (LowCardinality(Nullable(String)))
        if (e.evt() != null) escapeString(sb, e.evt()); else sb.append(NULL_VALUE);
        sb.append(TAB);

        // 21. quality (UInt8)
        sb.append(e.quality()).append(TAB);

        // 22. ingest_ts (DateTime64(3, 'UTC'))
        sb.append(e.getFormattedIngestTs());

        // End of row
        sb.append(LF);
    }

    private static void appendStringArray(StringBuilder sb, List<String> list) {
        if (list == null || list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('\'');
            escapeSingleQuotedString(sb, list.get(i));
            sb.append('\'');
        }
        sb.append(']');
    }

    private static void escapeString(StringBuilder sb, String str) {
        if (str == null) return;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else {
                sb.append(c);
            }
        }
    }

    private static void escapeSingleQuotedString(StringBuilder sb, String str) {
        if (str == null) return;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\'') {
                sb.append("\\'");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else {
                sb.append(c);
            }
        }
    }
}
