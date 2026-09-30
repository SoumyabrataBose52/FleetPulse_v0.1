package com.fleetpulse.normalizer.producer;

import com.fleetpulse.normalizer.config.NormalizerProperties;
import com.fleetpulse.normalizer.model.CanonicalTelemetryEvent;
import org.apache.avro.Schema;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * §4.3 & §5.1 Canonical Telemetry Event Avro Kafka Producer.
 *
 * Encodes canonical events into binary Avro matching canonical_event.avsc
 * and dispatches to Kafka partitioned by vehicle_pid.
 */
@Component
public class CanonicalProducer {

    private static final Logger log = LoggerFactory.getLogger(CanonicalProducer.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final NormalizerProperties properties;
    private final Schema avroSchema;

    public CanonicalProducer(
        KafkaTemplate<String, byte[]> kafkaTemplate,
        NormalizerProperties properties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.avroSchema = loadAvroSchema();
    }

    public CompletableFuture<?> send(CanonicalTelemetryEvent event) throws IOException {
        byte[] avroBytes = event.toAvroBinary(avroSchema);
        String topic = properties.kafka().canonicalTopic();
        String key = event.vehiclePid();

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, avroBytes);
        record.headers().add(new RecordHeader("tenant_id", String.valueOf(event.tenantId()).getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("oem", event.oem().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("schema_ver", String.valueOf(event.schemaVer()).getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ts_event", String.valueOf(event.tsEvent()).getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ts_ingest", String.valueOf(event.tsIngest()).getBytes(StandardCharsets.UTF_8)));

        return kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to produce canonical event for vehicle_pid: {}", key, ex);
            }
        });
    }

    public Schema getAvroSchema() {
        return avroSchema;
    }

    private Schema loadAvroSchema() {
        try {
            // Try classpath first
            ClassPathResource resource = new ClassPathResource("schemas/canonical_event.avsc");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return new Schema.Parser().parse(is);
                }
            }

            // Fallback filesystem
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
