package com.fleetpulse.streamengine.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public record TripEvent(
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
