package com.fleetpulse.normalizer.consumer;

import com.fleetpulse.normalizer.engine.NormalizationResult;
import com.fleetpulse.normalizer.engine.NormalizerEngine;
import com.fleetpulse.normalizer.mapping.MappingRegistry;
import com.fleetpulse.normalizer.metrics.NormalizerMetrics;
import com.fleetpulse.normalizer.producer.CanonicalProducer;
import com.fleetpulse.normalizer.producer.DlqProducer;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * §4.5 & §15.6 Kafka Consumer for Raw Telematics and OEM Mapping Configuration updates.
 */
@Component
public class NormalizerConsumer {

    private static final Logger log = LoggerFactory.getLogger(NormalizerConsumer.class);

    private final NormalizerEngine engine;
    private final CanonicalProducer canonicalProducer;
    private final DlqProducer dlqProducer;
    private final MappingRegistry mappingRegistry;
    private final NormalizerMetrics metrics;

    public NormalizerConsumer(
        NormalizerEngine engine,
        CanonicalProducer canonicalProducer,
        DlqProducer dlqProducer,
        MappingRegistry mappingRegistry,
        NormalizerMetrics metrics
    ) {
        this.engine = engine;
        this.canonicalProducer = canonicalProducer;
        this.dlqProducer = dlqProducer;
        this.mappingRegistry = mappingRegistry;
        this.metrics = metrics;
    }

    @KafkaListener(
        topics = "${normalizer.kafka.raw-topic:raw.telemetry}",
        groupId = "${spring.kafka.consumer.group-id:fleetpulse-normalizer}"
    )
    public void consumeRaw(ConsumerRecord<String, byte[]> record) {
        Timer.Sample sample = Timer.start();

        Map<String, String> headers = new HashMap<>();
        String oem = null;
        Integer schemaVer = null;
        long recvTs = System.currentTimeMillis();

        for (Header header : record.headers()) {
            String key = header.key();
            String val = new String(header.value(), StandardCharsets.UTF_8);
            headers.put(key, val);

            if ("oem".equalsIgnoreCase(key)) {
                oem = val;
            } else if ("schema_ver".equalsIgnoreCase(key)) {
                try { schemaVer = Integer.parseInt(val); } catch (NumberFormatException ignored) {}
            } else if ("recv_ts".equalsIgnoreCase(key)) {
                try { recvTs = Long.parseLong(val); } catch (NumberFormatException ignored) {}
            }
        }

        NormalizationResult result = engine.normalize(record.value(), oem, schemaVer, recvTs, headers);

        if (result.success()) {
            try {
                canonicalProducer.send(result.canonicalEvent());
                metrics.recordEvent("valid", result.canonicalEvent().oem());
            } catch (IOException e) {
                log.error("Failed to serialize canonical event to Avro: {}", e.getMessage(), e);
            }
        } else {
            dlqProducer.send(result.dlqEvent());
            metrics.recordEvent("dlq", result.dlqEvent().oem());
            metrics.recordDlq(result.dlqEvent().reasonCode());
        }

        sample.stop(metrics.getTimer());
    }

    @KafkaListener(
        topics = "${normalizer.kafka.mappings-topic:config.oem-mappings}",
        groupId = "fleetpulse-normalizer-mappings"
    )
    public void consumeMappingUpdate(ConsumerRecord<String, String> record) {
        log.info("Received OEM mapping update on topic {} with key: {}", record.topic(), record.key());
        try {
            mappingRegistry.registerJson(record.value());
            log.info("Successfully hot-reloaded OEM mapping: {}", record.key());
        } catch (Exception e) {
            log.error("Failed to hot-reload OEM mapping for key {}: {}", record.key(), e.getMessage(), e);
        }
    }
}
