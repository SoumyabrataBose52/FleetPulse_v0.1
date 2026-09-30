package com.fleetpulse.streamengine.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public record IdleEvent(
    String idleId,
    String vehiclePid,
    int tenantId,
    Integer driverId,
    long startTs,
    long endTs,
    int durationS,
    int latE6,
    int lonE6,
    String geohash7,
    double fuelBurnedL,
    double cost,
    double avoidableCost,
    double co2Kg
) {

    public GenericRecord toGenericRecord(Schema schema) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("idle_id", idleId);
        record.put("vehicle_pid", vehiclePid);
        record.put("tenant_id", tenantId);
        record.put("driver_id", driverId);
        record.put("start_ts", startTs);
        record.put("end_ts", endTs);
        record.put("duration_s", durationS);
        record.put("lat_e6", latE6);
        record.put("lon_e6", lonE6);
        record.put("geohash7", geohash7);
        record.put("fuel_burned_l", fuelBurnedL);
        record.put("cost", cost);
        record.put("avoidable_cost", avoidableCost);
        record.put("co2_kg", co2Kg);
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
