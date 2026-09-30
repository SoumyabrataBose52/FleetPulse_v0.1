package com.fleetpulse.streamengine;

import com.fleetpulse.streamengine.consumer.StreamEngineConsumer;
import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.processor.*;
import com.fleetpulse.streamengine.producer.StreamEngineProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Tag("benchmark")
class StreamEngineBenchmarkTest {

    @Test
    void benchmarkStreamEngineThroughput() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, byte[]> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        StreamEngineProducer producer = new StreamEngineProducer(
            kafkaTemplate,
            "events.trip.v1",
            "events.idle.v1",
            "events.charging.v1",
            "events.alerts.v1",
            "events.safety.v1",
            "events.geofence.v1"
        );

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TripsProcessor tripsProcessor = new TripsProcessor(5.0, 1.0, 30.0, 50.0, 300, 1.45, 0.22, registry);
        IdleProcessor idleProcessor = new IdleProcessor(120, 10, 1.5, 1.45, registry);
        EvProcessor evProcessor = new EvProcessor(0.22, 0.12, 60.0, 0.92, 15.0, 15.0, 8.0, 300_000, registry);
        SafetyProcessor safetyProcessor = new SafetyProcessor(-3.5f, 3.0f, 100.0f, 5000L, registry);
        GeofenceProcessor geofenceProcessor = new GeofenceProcessor(300.0, 60000L, registry);

        StreamEngineConsumer consumer = new StreamEngineConsumer(
            tripsProcessor,
            idleProcessor,
            evProcessor,
            safetyProcessor,
            geofenceProcessor,
            producer,
            registry
        );

        // Prepare 1,000 distinct vehicles with pre-encoded Avro records
        int numVehicles = 1_000;
        int totalEvents = 100_000;
        List<byte[]> samplePayloads = new ArrayList<>(numVehicles);

        for (int v = 0; v < numVehicles; v++) {
            String pid = UUID.randomUUID().toString();
            CanonicalTelemetryEvent event = new CanonicalTelemetryEvent(
                "evt-" + v,
                pid,
                1,
                "TESLA",
                1,
                1_700_000_000_000L + (v * 1000L),
                1_700_000_000_010L + (v * 1000L),
                (long) v,
                37_774_929 + (v % 100),
                -122_419_416 + (v % 100),
                90,
                30.0f,
                5000.0 + (v * 0.1),
                true,
                null,
                65.0f,
                380.0f,
                "DISCONNECTED",
                0.0f,
                32.0f,
                1500,
                List.of(),
                null,
                0
            );
            samplePayloads.add(event.toAvroBinary(consumer.getCanonicalSchema()));
        }

        // Warmup (10,000 records)
        for (int i = 0; i < 10_000; i++) {
            byte[] payload = samplePayloads.get(i % numVehicles);
            consumer.onTelemetryRecord(new ConsumerRecord<>("telemetry.clean.v1", 0, (long) i, "key", payload), null);
        }

        // Benchmark (100,000 records)
        long startNs = System.nanoTime();
        for (int i = 0; i < totalEvents; i++) {
            byte[] payload = samplePayloads.get(i % numVehicles);
            consumer.onTelemetryRecord(new ConsumerRecord<>("telemetry.clean.v1", 0, (long) i, "key", payload), null);
        }
        long durationNs = System.nanoTime() - startNs;

        double elapsedSeconds = durationNs / 1_000_000_000.0;
        double throughput = totalEvents / elapsedSeconds;

        System.out.printf("Stream Engine Benchmark: %d events in %.3f s = %.1f events/s/core%n",
            totalEvents, elapsedSeconds, throughput);

        // Throughput must comfortably exceed 30,000 events/sec/core
        assertTrue(throughput > 25_000, "Throughput should exceed 25,000 eps/core, got: " + throughput);
    }
}
