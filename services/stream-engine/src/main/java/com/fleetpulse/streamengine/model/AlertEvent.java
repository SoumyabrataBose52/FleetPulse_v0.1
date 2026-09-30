package com.fleetpulse.streamengine.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

public record AlertEvent(
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
