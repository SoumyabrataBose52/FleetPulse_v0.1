package com.fleetpulse.normalizer.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * §5.1 FleetPulse Canonical Telemetry Event Java representation.
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
            Schema csSchema = schema.getField("charge_state").schema().getTypes().stream()
                .filter(s -> s.getType() == Schema.Type.ENUM)
                .findFirst().orElse(null);
            if (csSchema != null) {
                record.put("charge_state", new GenericData.EnumSymbol(csSchema, chargeState));
            } else {
                record.put("charge_state", chargeState);
            }
        } else {
            record.put("charge_state", null);
        }

        record.put("charge_kw", chargeKw);
        record.put("coolant_c", coolantC);
        record.put("rpm", rpm);
        record.put("dtc", dtc != null ? dtc : new ArrayList<>());

        if (evt != null) {
            Schema evtSchema = schema.getField("evt").schema().getTypes().stream()
                .filter(s -> s.getType() == Schema.Type.ENUM)
                .findFirst().orElse(null);
            if (evtSchema != null) {
                record.put("evt", new GenericData.EnumSymbol(evtSchema, evt));
            } else {
                record.put("evt", evt);
            }
        } else {
            record.put("evt", null);
        }

        record.put("quality", quality);
        return record;
    }

    public byte[] toAvroBinary(Schema schema) throws IOException {
        GenericRecord record = toGenericRecord(schema);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        GenericDatumWriter<GenericRecord> writer = new GenericDatumWriter<>(schema);
        writer.write(record, encoder);
        encoder.flush();
        return out.toByteArray();
    }
}
