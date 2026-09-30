package com.fleetpulse.streamengine.producer;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka producer for publishing processed domain events (§4.3, §15.9):
 * trips, idling episodes, charging sessions, and operational alerts.
 */
@Service
public class StreamEngineProducer {

    private static final Logger log = LoggerFactory.getLogger(StreamEngineProducer.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final String tripsTopic;
    private final String idleTopic;
    private final String chargingTopic;
    private final String alertsTopic;
    private final String safetyTopic;
    private final String geofenceTopic;

    public StreamEngineProducer(
        KafkaTemplate<String, byte[]> kafkaTemplate,
        @Value("${fleetpulse.stream.topics.trips:events.trip.v1}") String tripsTopic,
        @Value("${fleetpulse.stream.topics.idle:events.idle.v1}") String idleTopic,
        @Value("${fleetpulse.stream.topics.charging:events.charging.v1}") String chargingTopic,
        @Value("${fleetpulse.stream.topics.alerts:events.alerts.v1}") String alertsTopic,
        @Value("${fleetpulse.stream.topics.safety:events.safety.v1}") String safetyTopic,
        @Value("${fleetpulse.stream.topics.geofence:events.geofence.v1}") String geofenceTopic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.tripsTopic = tripsTopic;
        this.idleTopic = idleTopic;
        this.chargingTopic = chargingTopic;
        this.alertsTopic = alertsTopic;
        this.safetyTopic = safetyTopic;
        this.geofenceTopic = geofenceTopic;
    }

    public CompletableFuture<?> sendTrip(String vehiclePid, byte[] avroPayload) {
        return send(tripsTopic, vehiclePid, avroPayload);
    }

    public CompletableFuture<?> sendIdle(String vehiclePid, byte[] avroPayload) {
        return send(idleTopic, vehiclePid, avroPayload);
    }

    public CompletableFuture<?> sendCharging(String vehiclePid, byte[] avroPayload) {
        return send(chargingTopic, vehiclePid, avroPayload);
    }

    public CompletableFuture<?> sendAlert(String vehiclePid, byte[] avroPayload) {
        return send(alertsTopic, vehiclePid, avroPayload);
    }

    public CompletableFuture<?> sendSafety(String vehiclePid, byte[] avroPayload) {
        return send(safetyTopic, vehiclePid, avroPayload);
    }

    public CompletableFuture<?> sendGeofence(String vehiclePid, byte[] avroPayload) {
        return send(geofenceTopic, vehiclePid, avroPayload);
    }

    private CompletableFuture<?> send(String topic, String key, byte[] payload) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, payload);
        return kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to send record to topic {}: {}", topic, ex.getMessage(), ex);
            }
        });
    }

    public String getTripsTopic() { return tripsTopic; }
    public String getIdleTopic() { return idleTopic; }
    public String getChargingTopic() { return chargingTopic; }
    public String getAlertsTopic() { return alertsTopic; }
}
