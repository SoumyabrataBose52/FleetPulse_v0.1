package com.fleetpulse.insightsink.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public record IdleEventRecord(
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
    double fuelBurnL,
    double cost,
    double co2Kg,
    double avoidableCost,
    String powertrain
) {

    public static IdleEventRecord fromGenericRecord(GenericRecord record) {
        return new IdleEventRecord(
            record.get("idle_id").toString(),
            record.get("vehicle_pid").toString(),
            (Integer) record.get("tenant_id"),
            record.get("driver_id") != null ? ((Number) record.get("driver_id")).intValue() : null,
            (Long) record.get("start_ts"),
            (Long) record.get("end_ts"),
            (Integer) record.get("duration_s"),
            (Integer) record.get("lat_e6"),
            (Integer) record.get("lon_e6"),
            record.get("geohash7") != null ? record.get("geohash7").toString() : "",
            ((Number) record.get("fuel_burn_l")).doubleValue(),
            ((Number) record.get("cost")).doubleValue(),
            ((Number) record.get("co2_kg")).doubleValue(),
            ((Number) record.get("avoidable_cost")).doubleValue(),
            record.get("powertrain") != null ? record.get("powertrain").toString() : "ICE"
        );
    }

    public static IdleEventRecord fromAvroBinary(byte[] bytes, Schema schema) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(in, null);
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        GenericRecord record = reader.read(null, decoder);
        return fromGenericRecord(record);
    }
}
