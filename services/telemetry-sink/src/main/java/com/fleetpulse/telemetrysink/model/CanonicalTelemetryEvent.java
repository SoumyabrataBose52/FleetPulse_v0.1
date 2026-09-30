package com.fleetpulse.telemetrysink.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * §5.1 FleetPulse Canonical Telemetry Event Java representation in Telemetry Sink.
 */
public record CanonicalTelemetryEvent(
    String eventId,
    String vehiclePid,
    int tenantId,
    String oem,
    int schemaVer,
    long tsEvent,
    long tsIngest,
    Long seq,
    Integer latE6,
    Integer lonE6,
    Integer headingDeg,
    Float speedKmh,
    Double odoKm,
    Boolean ignition,
    Float fuelPct,
    Float socPct,
    Float battVoltageV,
    String chargeState,
    Float chargeKw,
    Float coolantC,
    Integer rpm,
    List<String> dtc,
    String evt,
    int quality
) {

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneOffset.UTC);

    public long getEventIdAsUInt64() {
        if (eventId == null || eventId.isEmpty()) {
            return 0L;
        }
        try {
            // Normalizer generates 16-hex char prefix (§4.5)
            if (eventId.length() == 16 && eventId.matches("^[0-9a-fA-F]{16}$")) {
                return Long.parseUnsignedLong(eventId, 16);
            }
            // Standard decimal representation
            if (eventId.matches("^[0-9]+$")) {
                return Long.parseUnsignedLong(eventId, 10);
            }
            // General hex string
            if (eventId.matches("^[0-9a-fA-F]+$")) {
                return Long.parseUnsignedLong(eventId, 16);
            }
        } catch (Exception ignored) {
        }
        // Fallback: 64-bit hash of string
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(eventId.getBytes(StandardCharsets.UTF_8));
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFFL);
            }
            return hash;
        } catch (Exception e) {
            return (long) eventId.hashCode();
        }
    }

    public String getFormattedTs() {
        return TS_FORMATTER.format(Instant.ofEpochMilli(tsEvent));
    }

    public String getFormattedIngestTs() {
        return TS_FORMATTER.format(Instant.ofEpochMilli(tsIngest));
    }

    public static CanonicalTelemetryEvent fromGenericRecord(GenericRecord record) {
        List<String> dtcList = new ArrayList<>();
        Object dtcObj = record.get("dtc");
        if (dtcObj instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) dtcList.add(item.toString());
            }
        }

        Object csObj = record.get("charge_state");
        String chargeState = csObj != null ? csObj.toString() : null;

        Object evtObj = record.get("evt");
        String evt = evtObj != null ? evtObj.toString() : null;

        return new CanonicalTelemetryEvent(
            record.get("event_id").toString(),
            record.get("vehicle_pid").toString(),
            (Integer) record.get("tenant_id"),
            record.get("oem").toString(),
            (Integer) record.get("schema_ver"),
            (Long) record.get("ts_event"),
            (Long) record.get("ts_ingest"),
            record.get("seq") != null ? ((Number) record.get("seq")).longValue() : null,
            (Integer) record.get("lat_e6"),
            (Integer) record.get("lon_e6"),
            (Integer) record.get("heading_deg"),
            record.get("speed_kmh") != null ? ((Number) record.get("speed_kmh")).floatValue() : null,
            record.get("odo_km") != null ? ((Number) record.get("odo_km")).doubleValue() : null,
            (Boolean) record.get("ignition"),
            record.get("fuel_pct") != null ? ((Number) record.get("fuel_pct")).floatValue() : null,
            record.get("soc_pct") != null ? ((Number) record.get("soc_pct")).floatValue() : null,
            record.get("batt_voltage_v") != null ? ((Number) record.get("batt_voltage_v")).floatValue() : null,
            chargeState,
            record.get("charge_kw") != null ? ((Number) record.get("charge_kw")).floatValue() : null,
            record.get("coolant_c") != null ? ((Number) record.get("coolant_c")).floatValue() : null,
            (Integer) record.get("rpm"),
            dtcList,
            evt,
            (Integer) record.get("quality")
        );
    }

    public static CanonicalTelemetryEvent fromAvroBinary(byte[] bytes, Schema schema) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(in, null);
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        GenericRecord record = reader.read(null, decoder);
        return fromGenericRecord(record);
    }

    public GenericRecord toGenericRecord(Schema schema) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("event_id", eventId);
        record.put("vehicle_pid", vehiclePid);
        record.put("tenant_id", tenantId);
        record.put("oem", oem);
        record.put("schema_ver", schemaVer);
        record.put("ts_event", tsEvent);
        record.put("ts_ingest", tsIngest);
        record.put("seq", seq);
        record.put("lat_e6", latE6);
        record.put("lon_e6", lonE6);
        record.put("heading_deg", headingDeg);
        record.put("speed_kmh", speedKmh);
        record.put("odo_km", odoKm);
        record.put("ignition", ignition);
        record.put("fuel_pct", fuelPct);
        record.put("soc_pct", socPct);
        record.put("batt_voltage_v", battVoltageV);
        if (chargeState != null) {
            Schema csEnum = schema.getField("charge_state").schema().getTypes().stream()
                .filter(s -> s.getType() == Schema.Type.ENUM)
                .findFirst().orElse(null);
            record.put("charge_state", csEnum != null ? new GenericData.EnumSymbol(csEnum, chargeState) : null);
        } else {
            record.put("charge_state", null);
        }
        record.put("charge_kw", chargeKw);
        record.put("coolant_c", coolantC);
        record.put("rpm", rpm);
        record.put("dtc", dtc != null ? dtc : List.of());
        if (evt != null) {
            Schema evtEnum = schema.getField("evt").schema().getTypes().stream()
                .filter(s -> s.getType() == Schema.Type.ENUM)
                .findFirst().orElse(null);
            record.put("evt", evtEnum != null ? new GenericData.EnumSymbol(evtEnum, evt) : null);
        } else {
            record.put("evt", null);
        }
        record.put("quality", quality);
        return record;
    }

    public byte[] toAvroBinary(Schema schema) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        GenericDatumWriter<GenericRecord> writer = new GenericDatumWriter<>(schema);
        writer.write(toGenericRecord(schema), encoder);
        encoder.flush();
        return out.toByteArray();
    }
}
