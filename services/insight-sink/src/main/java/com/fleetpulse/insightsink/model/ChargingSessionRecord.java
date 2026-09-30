package com.fleetpulse.insightsink.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public record ChargingSessionRecord(
    String sessionId,
    String vehiclePid,
    int tenantId,
    Integer chargerId,
    Integer depotId,
    long startTs,
    long endTs,
    float startSocPct,
    float endSocPct,
    double energyKwh,
    float avgKw,
    double pricePaid,
    double baselineCost,
    double smartCost,
    Float sohEstimate
) {

    public static ChargingSessionRecord fromGenericRecord(GenericRecord record) {
        return new ChargingSessionRecord(
            record.get("session_id").toString(),
            record.get("vehicle_pid").toString(),
            (Integer) record.get("tenant_id"),
            record.get("charger_id") != null ? ((Number) record.get("charger_id")).intValue() : null,
            record.get("depot_id") != null ? ((Number) record.get("depot_id")).intValue() : null,
            (Long) record.get("start_ts"),
            (Long) record.get("end_ts"),
            ((Number) record.get("start_soc_pct")).floatValue(),
            ((Number) record.get("end_soc_pct")).floatValue(),
            ((Number) record.get("energy_kwh")).doubleValue(),
            ((Number) record.get("avg_kw")).floatValue(),
            ((Number) record.get("price_paid")).doubleValue(),
            ((Number) record.get("baseline_cost")).doubleValue(),
            ((Number) record.get("smart_cost")).doubleValue(),
            record.get("soh_estimate") != null ? ((Number) record.get("soh_estimate")).floatValue() : null
        );
    }

    public static ChargingSessionRecord fromAvroBinary(byte[] bytes, Schema schema) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(in, null);
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        GenericRecord record = reader.read(null, decoder);
        return fromGenericRecord(record);
    }
}
