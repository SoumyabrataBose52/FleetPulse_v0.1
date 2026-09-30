package com.fleetpulse.telemetrysink.benchmark;

import com.fleetpulse.telemetrysink.model.CanonicalTelemetryEvent;
import com.fleetpulse.telemetrysink.sink.ClickHouseClient;
import com.fleetpulse.telemetrysink.sink.TelemetrySinkService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("benchmark")
class TelemetrySinkBenchmarkTest {

    @Test
    void benchmarkBatchFormattingAndProcessing() throws Exception {
        ClickHouseClient noopClient = new ClickHouseClient() {
            @Override
            public void insertTelemetryBatch(List<CanonicalTelemetryEvent> events, String deduplicationToken) {
                // Simulates receiving batch
            }

            @Override
            public boolean ping() {
                return true;
            }
        };

        TelemetrySinkService service = new TelemetrySinkService(noopClient, new SimpleMeterRegistry());

        int totalEvents = 100_000;
        int batchSize = 5_000;

        CanonicalTelemetryEvent sampleEvent = new CanonicalTelemetryEvent(
            "00000000000000ff", "11111111-2222-3333-4444-555555555555",
            42, "B", 1, 1700000000000L, 1700000000100L, 555L,
            40712800, -74006000, 90, 50.5f, 25000.5, true,
            80.0f, 75.5f, 350.0f, "CHARGING", 22.0f, 90.0f, 1800,
            List.of("P0300", "C0035"), "HARSH_BRAKE", 0
        );

        byte[] sampleAvro = sampleEvent.toAvroBinary(service.getAvroSchema());

        List<ConsumerRecord<String, byte[]>> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            batch.add(new ConsumerRecord<>("telemetry.clean.v1", 0, (long) i, "k", sampleAvro));
        }

        // Warmup
        for (int i = 0; i < 4; i++) {
            service.processBatch(batch);
        }

        int iterations = totalEvents / batchSize;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            service.processBatch(batch);
        }

        long durationNs = System.nanoTime() - startTime;
        double durationSec = durationNs / 1_000_000_000.0;
        double throughput = totalEvents / durationSec;

        System.out.printf("[TelemetrySinkBenchmark] Processed %d events in %.3f s -> %.1f events/second/core%n",
            totalEvents, durationSec, throughput);

        assertTrue(throughput > 50_000.0, "Throughput must exceed 50,000 eps/core, got: " + throughput);
    }
}
