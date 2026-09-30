package com.fleetpulse.insightsink.model;

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
import java.util.HashMap;
import java.util.Map;

public record AlertEventRecord(
    String alertId,
    String alertKey,
    int tenantId,
    String vehiclePid,
    String type,
    String severity,
    long ts,
    String message,
    Map<String, String> details,
    String insightRef
) {

    public static AlertEventRecord fromGenericRecord(GenericRecord record) {
        Map<String, String> detailsMap = new HashMap<>();
        Object detailsObj = record.get("details");
        if (detailsObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    detailsMap.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
        }

        return new AlertEventRecord(
            record.get("alert_id").toString(),
            record.get("alert_key").toString(),
            (Integer) record.get("tenant_id"),
            record.get("vehicle_pid").toString(),
            record.get("type").toString(),
            record.get("severity").toString(),
            (Long) record.get("ts"),
            record.get("message").toString(),
            detailsMap,
            record.get("insight_ref") != null ? record.get("insight_ref").toString() : null
        );
    }

    public static AlertEventRecord fromAvroBinary(byte[] bytes, Schema schema) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(in, null);
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        GenericRecord record = reader.read(null, decoder);
        return fromGenericRecord(record);
    }

    public GenericRecord toGenericRecord(Schema schema) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("alert_id", alertId);
        record.put("alert_key", alertKey);
        record.put("tenant_id", tenantId);
        record.put("vehicle_pid", vehiclePid);
        record.put("type", type);
        Schema sevEnum = schema.getField("severity").schema();
        record.put("severity", new GenericData.EnumSymbol(sevEnum, severity));
        record.put("ts", ts);
        record.put("message", message);
        record.put("details", details != null ? details : Map.of());
        record.put("insight_ref", insightRef);
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
