package com.fleetpulse.gateway.service;

import com.fleetpulse.gateway.config.GatewayProperties;
import com.fleetpulse.gateway.exception.BackpressureException;
import com.fleetpulse.gateway.exception.PrecheckException;
import com.fleetpulse.gateway.metrics.GatewayMetrics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * §4.4 & §4.6 Ingestion Service.
 *
 * Implements bounded concurrency queues, back-pressure shedding (HTTP 429),
 * fast syntactic validation, and asynchronous Kafka publishing.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final GatewayProperties properties;
    private final KeyExtractor keyExtractor;
    private final GatewayMetrics metrics;

    private final AtomicInteger inFlightCount = new AtomicInteger(0);

    public IngestService(
        KafkaTemplate<String, byte[]> kafkaTemplate,
        GatewayProperties properties,
        KeyExtractor keyExtractor,
        GatewayMetrics metrics
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.keyExtractor = keyExtractor;
        this.metrics = metrics;
    }

    public int processBatch(String rawBody, String oem, String traceparent) {
        // 1. Syntactic precheck (§4.4)
        if (rawBody == null || rawBody.isBlank()) {
            throw new PrecheckException("Payload batch body cannot be empty");
        }

        byte[] rawBytes = rawBody.getBytes(StandardCharsets.UTF_8);
        if (rawBytes.length > properties.maxPayloadBytes()) {
            throw new PrecheckException("Payload batch exceeds max limit of " + properties.maxPayloadBytes() + " bytes");
        }

        // 2. Back-pressure queue check (§4.6)
        int currentInFlight = inFlightCount.get();
        metrics.setQueueDepth(currentInFlight);

        if (currentInFlight >= properties.queue().getHighWatermarkLimit()) {
            metrics.recordRejected429();
            throw new BackpressureException(
                "Ingestion queue depth " + currentInFlight + " exceeds high watermark limit " +
                properties.queue().getHighWatermarkLimit()
            );
        }

        // 3. Split batch into NDJSON lines (§4.4, §9)
        String[] lines = rawBody.split("\\r?\\n");
        List<String> validEvents = new ArrayList<>(lines.length);
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                validEvents.add(trimmed);
            }
        }

        if (validEvents.isEmpty()) {
            throw new PrecheckException("No valid non-empty events found in batch");
        }

        if (validEvents.size() > properties.maxBatchSize()) {
            throw new PrecheckException("Batch event count " + validEvents.size() + " exceeds max allowed " + properties.maxBatchSize());
        }

        long recvTs = System.currentTimeMillis();
        String topic = properties.kafka().rawTopic();

        // 4. Asynchronous Kafka dispatch (§4.3)
        for (String event : validEvents) {
            String partitionKey = keyExtractor.extractKey(event);
            byte[] payloadBytes = event.getBytes(StandardCharsets.UTF_8);

            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, partitionKey, payloadBytes);
            record.headers().add(new RecordHeader("oem", oem.getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("recv_ts", String.valueOf(recvTs).getBytes(StandardCharsets.UTF_8)));
            if (traceparent != null && !traceparent.isBlank()) {
                record.headers().add(new RecordHeader("traceparent", traceparent.getBytes(StandardCharsets.UTF_8)));
            }

            inFlightCount.incrementAndGet();
            kafkaTemplate.send(record).whenComplete((result, ex) -> {
                inFlightCount.decrementAndGet();
                if (ex != null) {
                    log.error("Failed to produce raw telematics event for key: {}", partitionKey, ex);
                }
            });
        }

        metrics.recordBatch(oem, validEvents.size(), rawBytes.length);
        return validEvents.size();
    }

    public int getInFlightCount() {
        return inFlightCount.get();
    }

    public void setInFlightCountForTesting(int count) {
        inFlightCount.set(count);
    }
}
