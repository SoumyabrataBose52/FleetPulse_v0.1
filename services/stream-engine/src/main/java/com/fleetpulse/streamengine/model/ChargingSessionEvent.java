package com.fleetpulse.streamengine.model;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public record ChargingSessionEvent(
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
    float avgPowerKw,
    float peakPowerKw,
    double baselineCost,
    double smartCost,
    double costSaving,
    Float sohEstimate
) {

    public GenericRecord toGenericRecord(Schema schema) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("session_id", sessionId);
        record.put("vehicle_pid", vehiclePid);
        record.put("tenant_id", tenantId);
        record.put("charger_id", chargerId);
        record.put("depot_id", depotId);
        record.put("start_ts", startTs);
        record.put("end_ts", endTs);
        record.put("start_soc_pct", startSocPct);
        record.put("end_soc_pct", endSocPct);
        record.put("energy_kwh", energyKwh);
        record.put("avg_power_kw", avgPowerKw);
        record.put("peak_power_kw", peakPowerKw);
        record.put("baseline_cost", baselineCost);
        record.put("smart_cost", smartCost);
        record.put("cost_saving", costSaving);
        record.put("soh_estimate", sohEstimate);
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
