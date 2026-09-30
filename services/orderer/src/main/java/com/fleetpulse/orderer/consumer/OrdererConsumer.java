package com.fleetpulse.orderer.consumer;

import com.fleetpulse.orderer.config.OrdererProperties;
import com.fleetpulse.orderer.dedupe.Deduplicator;
import com.fleetpulse.orderer.fastpath.FastPathProcessor;
import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;
import com.fleetpulse.orderer.producer.CleanProducer;
import com.fleetpulse.orderer.producer.LateProducer;
import com.fleetpulse.orderer.reorder.ReorderBuffer;
import com.fleetpulse.orderer.reorder.ReorderResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * §4.5 & §15.7 Kafka Consumer for Canonical Stream with Fast-Path, Dedupe, and Reordering.
 */
@Component
public class OrdererConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrdererConsumer.class);

    private final Deduplicator deduplicator;
    private final ReorderBuffer reorderBuffer;
    private final FastPathProcessor fastPathProcessor;
    private final CleanProducer cleanProducer;
    private final LateProducer lateProducer;
    private final OrdererProperties properties;

    public OrdererConsumer(
        Deduplicator deduplicator,
        ReorderBuffer reorderBuffer,
        FastPathProcessor fastPathProcessor,
        CleanProducer cleanProducer,
        LateProducer lateProducer,
        OrdererProperties properties
    ) {
        this.deduplicator = deduplicator;
        this.reorderBuffer = reorderBuffer;
        this.fastPathProcessor = fastPathProcessor;
        this.cleanProducer = cleanProducer;
        this.lateProducer = lateProducer;
        this.properties = properties;
    }

    @KafkaListener(
        topics = "${orderer.kafka.canonical-topic:telemetry.canonical.v1}",
        groupId = "${spring.kafka.consumer.group-id:fleetpulse-orderer}"
    )
    public void consume(ConsumerRecord<String, byte[]> record) {
        try {
            CanonicalTelemetryEvent event = CanonicalTelemetryEvent.fromAvroBinary(
                record.value(), cleanProducer.getAvroSchema()
            );

            // 1. Fast-Path Processing (§8.M1): immediate live state update with zero reorder lag
            if (properties.fastpathEnabled()) {
                fastPathProcessor.process(event);
            }

            // 2. Two-Tier Deduplication (§7.6)
            if (deduplicator.isDuplicate(event)) {
                log.debug("Dropped duplicate event: {} for vehicle: {}", event.eventId(), event.vehiclePid());
                return;
            }

            // 3. Watermark Reordering (§7.7)
            ReorderResult result = reorderBuffer.processEvent(event);

            // Dispatch clean ordered events
            for (CanonicalTelemetryEvent cleanEvent : result.cleanEvents()) {
                cleanProducer.send(cleanEvent);
            }

            // Dispatch late events
            for (CanonicalTelemetryEvent lateEvent : result.lateEvents()) {
                lateProducer.send(lateEvent);
            }

        } catch (IOException e) {
            log.error("Failed to deserialize canonical Avro event from partition {} offset {}: {}",
                record.partition(), record.offset(), e.getMessage(), e);
        }
    }

    @Scheduled(fixedRateString = "${orderer.silent-flush-interval-ms:1000}")
    public void flushSilentVehicles() {
        long now = System.currentTimeMillis();
        List<CanonicalTelemetryEvent> flushed = reorderBuffer.flushSilentVehicles(now);
        for (CanonicalTelemetryEvent event : flushed) {
            try {
                cleanProducer.send(event);
            } catch (IOException e) {
                log.error("Failed to emit flushed event for vehicle {}: {}", event.vehiclePid(), e.getMessage());
            }
        }
    }
}
