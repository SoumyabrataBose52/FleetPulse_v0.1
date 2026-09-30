package com.fleetpulse.normalizer.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpulse.normalizer.config.NormalizerProperties;
import com.fleetpulse.normalizer.model.DlqEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * §4.5 & §15.6 Dead Letter Queue (DLQ) Kafka Producer.
 *
 * Dispatches quarantined records to telemetry.dlq with structured reason codes
 * and original headers for MongoDB quarantine indexing.
 */
@Component
public class DlqProducer {

    private static final Logger log = LoggerFactory.getLogger(DlqProducer.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final NormalizerProperties properties;
    private final ObjectMapper objectMapper;

    public DlqProducer(
        KafkaTemplate<String, byte[]> kafkaTemplate,
        NormalizerProperties properties,
        ObjectMapper objectMapper
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<?> send(DlqEvent dlqEvent) {
        byte[] payloadBytes;
        try {
            payloadBytes = objectMapper.writeValueAsBytes(dlqEvent);
        } catch (JsonProcessingException e) {
            payloadBytes = dlqEvent.rawPayload().getBytes(StandardCharsets.UTF_8);
        }

        String topic = properties.kafka().dlqTopic();
        String key = dlqEvent.deviceId();

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, payloadBytes);
        record.headers().add(new RecordHeader("reason_code", dlqEvent.reasonCode().name().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("oem", dlqEvent.oem().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("error_message", dlqEvent.errorMessage().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("recv_ts", String.valueOf(dlqEvent.timestamp()).getBytes(StandardCharsets.UTF_8)));

        return kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to produce DLQ event for device: {}", key, ex);
            }
        });
    }
}
