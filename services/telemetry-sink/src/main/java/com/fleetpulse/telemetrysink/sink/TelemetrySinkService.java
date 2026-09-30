package com.fleetpulse.telemetrysink.sink;

import com.fleetpulse.telemetrysink.model.CanonicalTelemetryEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.avro.Schema;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Service orchestrating Avro deserialization, offset-based deduplication tokens, and batched ClickHouse insertion (§4.5, §5.2).
 */
@Service
public class TelemetrySinkService {

    private static final Logger log = LoggerFactory.getLogger(TelemetrySinkService.class);

    private final ClickHouseClient clickHouseClient;
    private final Schema avroSchema;
    private final Counter deserializationErrors;
    private final Counter sinkSuccessCount;

    public TelemetrySinkService(ClickHouseClient clickHouseClient, MeterRegistry registry) {
        this.clickHouseClient = clickHouseClient;
        this.avroSchema = loadAvroSchema();
        this.deserializationErrors = registry.counter("fleetpulse.telemetry.sink.deser.errors");
        this.sinkSuccessCount = registry.counter("fleetpulse.telemetry.sink.processed.total");
    }

    public Schema getAvroSchema() {
        return avroSchema;
    }

    public int processBatch(List<ConsumerRecord<String, byte[]>> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }

        // Group records by TopicPartition to construct contiguous deduplication tokens
        Map<TopicPartition, List<ConsumerRecord<String, byte[]>>> byPartition = new LinkedHashMap<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            TopicPartition tp = new TopicPartition(record.topic(), record.partition());
            byPartition.computeIfAbsent(tp, k -> new ArrayList<>()).add(record);
        }

        int totalInserted = 0;

        for (Map.Entry<TopicPartition, List<ConsumerRecord<String, byte[]>>> entry : byPartition.entrySet()) {
            TopicPartition tp = entry.getKey();
            List<ConsumerRecord<String, byte[]>> partitionRecords = entry.getValue();

            long firstOffset = partitionRecords.get(0).offset();
            long lastOffset = partitionRecords.get(partitionRecords.size() - 1).offset();

            // Construct ADR-004 deduplication token: topic-partition-firstOffset-lastOffset
            String dedupToken = String.format("%s-%d-%d-%d", tp.topic(), tp.partition(), firstOffset, lastOffset);

            List<CanonicalTelemetryEvent> events = new ArrayList<>(partitionRecords.size());
            for (ConsumerRecord<String, byte[]> record : partitionRecords) {
                try {
                    CanonicalTelemetryEvent event = CanonicalTelemetryEvent.fromAvroBinary(record.value(), avroSchema);
                    events.add(event);
                } catch (Exception e) {
                    deserializationErrors.increment();
                    log.error("Failed to deserialize Avro record at {}-{} offset {}: {}",
                        tp.topic(), tp.partition(), record.offset(), e.getMessage());
                }
            }

            if (!events.isEmpty()) {
                clickHouseClient.insertTelemetryBatch(events, dedupToken);
                totalInserted += events.size();
                sinkSuccessCount.increment(events.size());
            }
        }

        return totalInserted;
    }

    public void insertEvents(List<CanonicalTelemetryEvent> events, String deduplicationToken) {
        if (events == null || events.isEmpty()) {
            return;
        }
        clickHouseClient.insertTelemetryBatch(events, deduplicationToken);
        sinkSuccessCount.increment(events.size());
    }

    private Schema loadAvroSchema() {
        try {
            ClassPathResource resource = new ClassPathResource("schemas/canonical_event.avsc");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return new Schema.Parser().parse(is);
                }
            }

            Path path = Paths.get("../../libs/schemas/avro/canonical_event.avsc");
            if (Files.exists(path)) {
                return new Schema.Parser().parse(Files.newInputStream(path));
            }

            Path pathLocal = Paths.get("libs/schemas/avro/canonical_event.avsc");
            if (Files.exists(pathLocal)) {
                return new Schema.Parser().parse(Files.newInputStream(pathLocal));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load canonical_event.avsc: " + e.getMessage(), e);
        }
        throw new IllegalStateException("canonical_event.avsc not found in classpath or filesystem");
    }
}
