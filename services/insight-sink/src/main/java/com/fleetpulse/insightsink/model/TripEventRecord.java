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

public record TripEventRecord(
    String tripId,
    String vehiclePid,
    int tenantId,
    Integer driverId,
    long startTs,
    long endTs,
    int startLatE6,
    int startLonE6,
    String startGeohash7,
    int endLatE6,
    int endLonE6,
    String endGeohash7,
    double distanceKm,
    int durationS,
    int idleS,
    Double energyKwh,
    Double fuelL,
    double cost,
    double co2Kg,
    String status
) {

    public static TripEventRecord fromGenericRecord(GenericRecord record) {
        return new TripEventRecord(
            record.get("trip_id").toString(),
            record.get("vehicle_pid").toString(),
            (Integer) record.get("tenant_id"),
            record.get("driver_id") != null ? ((Number) record.get("driver_id")).intValue() : null,
            (Long) record.get("start_ts"),
            (Long) record.get("end_ts"),
            (Integer) record.get("start_lat_e6"),
            (Integer) record.get("start_lon_e6"),
            record.get("start_geohash7") != null ? record.get("start_geohash7").toString() : "",
            (Integer) record.get("end_lat_e6"),
            (Integer) record.get("end_lon_e6"),
            record.get("end_geohash7") != null ? record.get("end_geohash7").toString() : "",
            ((Number) record.get("distance_km")).doubleValue(),
            (Integer) record.get("duration_s"),
            (Integer) record.get("idle_s"),
            record.get("energy_kwh") != null ? ((Number) record.get("energy_kwh")).doubleValue() : null,
            record.get("fuel_l") != null ? ((Number) record.get("fuel_l")).doubleValue() : null,
            ((Number) record.get("cost")).doubleValue(),
            record.get("co2_kg") != null ? ((Number) record.get("co2_kg")).doubleValue() : 0.0,
            record.get("status") != null ? record.get("status").toString() : "COMPLETED"
        );
    }

    public static TripEventRecord fromAvroBinary(byte[] bytes, Schema schema) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(in, null);
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        GenericRecord record = reader.read(null, decoder);
        return fromGenericRecord(record);
    }

    public GenericRecord toGenericRecord(Schema schema) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("trip_id", tripId);
        record.put("vehicle_pid", vehiclePid);
        record.put("tenant_id", tenantId);
        record.put("driver_id", driverId);
        record.put("start_ts", startTs);
        record.put("end_ts", endTs);
        record.put("start_lat_e6", startLatE6);
        record.put("start_lon_e6", startLonE6);
        record.put("start_geohash7", startGeohash7);
        record.put("end_lat_e6", endLatE6);
        record.put("end_lon_e6", endLonE6);
        record.put("end_geohash7", endGeohash7);
        record.put("distance_km", distanceKm);
        record.put("duration_s", durationS);
        record.put("idle_s", idleS);
        record.put("energy_kwh", energyKwh);
        record.put("fuel_l", fuelL);
        record.put("cost", cost);
        record.put("co2_kg", co2Kg);
        if (status != null) {
            Schema statusEnum = schema.getField("status").schema();
            record.put("status", new GenericData.EnumSymbol(statusEnum, status));
        }
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
