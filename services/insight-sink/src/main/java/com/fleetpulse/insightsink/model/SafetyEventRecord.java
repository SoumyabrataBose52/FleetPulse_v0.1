package com.fleetpulse.insightsink.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public record SafetyEventRecord(
    String eventId,
    String vehiclePid,
    int tenantId,
    Integer driverId,
    long ts,
    String eventType,
    float severity,
    int latE6,
    int lonE6,
    float speedKmh
) {

    public static SafetyEventRecord fromGenericRecord(GenericRecord record) {
        return new SafetyEventRecord(
            record.get("event_id").toString(),
            record.get("vehicle_pid").toString(),
            (Integer) record.get("tenant_id"),
            record.get("driver_id") != null ? ((Number) record.get("driver_id")).intValue() : null,
            (Long) record.get("ts"),
            record.get("event_type").toString(),
            ((Number) record.get("severity")).floatValue(),
            (Integer) record.get("lat_e6"),
            (Integer) record.get("lon_e6"),
            ((Number) record.get("speed_kmh")).floatValue()
        );
    }

    public static SafetyEventRecord fromAvroBinary(byte[] bytes, Schema schema) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(in, null);
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        GenericRecord record = reader.read(null, decoder);
        return fromGenericRecord(record);
    }
}
