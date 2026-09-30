package com.fleetpulse.telemetrysink.consumer;

import com.fleetpulse.telemetrysink.sink.TelemetrySinkService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Batched Kafka consumer reading clean and late telemetry streams and dispatching to ClickHouse sink (§4.5, §5.2).
 */
@Component
public class TelemetrySinkConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetrySinkConsumer.class);

    private final TelemetrySinkService sinkService;

    public TelemetrySinkConsumer(TelemetrySinkService sinkService) {
        this.sinkService = sinkService;
    }

    @KafkaListener(
        topics = {
            "${fleetpulse.sink.topics.clean:telemetry.clean.v1}",
            "${fleetpulse.sink.topics.late:telemetry.late.v1}"
        },
        containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        if (records == null || records.isEmpty()) {
            if (ack != null) ack.acknowledge();
            return;
        }

        try {
            int inserted = sinkService.processBatch(records);
            log.debug("Consumed batch of {} Kafka records, inserted {} rows into ClickHouse", records.size(), inserted);

            // Acknowledge synchronously AFTER successful database insertion
            if (ack != null) {
                ack.acknowledge();
            }
        } catch (Exception e) {
            log.error("Failed to process batch of {} records: {}. Offsets will NOT be committed.",
                records.size(), e.getMessage(), e);
            throw e; // Rethrow to prevent commit and trigger retry
        }
    }
}
