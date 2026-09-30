package com.fleetpulse.streamengine.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public record GeofenceEvent(
    String eventId,
    String vehiclePid,
    int tenantId,
    int geofenceId,
    String geofenceType,
    String transition,
    long ts,
    int latE6,
    int lonE6
) {

    public GenericRecord toGenericRecord(Schema schema) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("event_id", eventId);
        record.put("vehicle_pid", vehiclePid);
        record.put("tenant_id", tenantId);
        record.put("geofence_id", geofenceId);
        Schema gtEnum = schema.getField("geofence_type").schema();
        record.put("geofence_type", new GenericData.EnumSymbol(gtEnum, geofenceType));
        Schema trEnum = schema.getField("transition").schema();
        record.put("transition", new GenericData.EnumSymbol(trEnum, transition));
        record.put("ts", ts);
        record.put("lat_e6", latE6);
        record.put("lon_e6", lonE6);
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
