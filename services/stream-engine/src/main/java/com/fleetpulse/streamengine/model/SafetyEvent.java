package com.fleetpulse.streamengine.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public record SafetyEvent(
    String eventId,
    String vehiclePid,
    int tenantId,
    Integer driverId,
    long ts,
    String eventType,
    float severity,
    int latE6,
    int lonE6,
    float speedKmh,
    float speedLimitKmh,
    float accelMs2,
    float weight
) {

    public GenericRecord toGenericRecord(Schema schema) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("event_id", eventId);
        record.put("vehicle_pid", vehiclePid);
        record.put("tenant_id", tenantId);
        record.put("driver_id", driverId);
        record.put("ts", ts);
        Schema etEnum = schema.getField("event_type").schema();
        record.put("event_type", new GenericData.EnumSymbol(etEnum, eventType));
        record.put("severity", severity);
        record.put("lat_e6", latE6);
        record.put("lon_e6", lonE6);
        record.put("speed_kmh", speedKmh);
        record.put("speed_limit_kmh", speedLimitKmh);
        record.put("accel_ms2", accelMs2);
        record.put("weight", weight);
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
