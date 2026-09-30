package com.fleetpulse.orderer.producer;

import com.fleetpulse.orderer.config.OrdererProperties;
import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;
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
 * §4.3 & §15.7 Kafka Producer for Late Telematics Stream (telemetry.late.v1).
 */
@Component
public class LateProducer {

    private static final Logger log = LoggerFactory.getLogger(LateProducer.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final OrdererProperties properties;
    private final Schema avroSchema;

    public LateProducer(
        KafkaTemplate<String, byte[]> kafkaTemplate,
        OrdererProperties properties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.avroSchema = loadAvroSchema();
    }

    public CompletableFuture<?> send(CanonicalTelemetryEvent event) throws IOException {
        byte[] avroBytes = event.toAvroBinary(avroSchema);
        String topic = properties.kafka().lateTopic();
        String key = event.vehiclePid();

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, avroBytes);
        record.headers().add(new RecordHeader("tenant_id", String.valueOf(event.tenantId()).getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("oem", event.oem().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ts_event", String.valueOf(event.tsEvent()).getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("quality", String.valueOf(event.quality()).getBytes(StandardCharsets.UTF_8)));

        return kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to produce late event for vehicle_pid: {}", key, ex);
            }
        });
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
