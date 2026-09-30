package com.fleetpulse.insightsink.consumer;

import com.fleetpulse.insightsink.model.*;
import com.fleetpulse.insightsink.sink.*;
import org.apache.avro.Schema;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Kafka consumer orchestrating idempotent writes across PostgreSQL, MongoDB, ClickHouse, and Redis (§4.5, §15.8).
 */
@Component
public class InsightSinkConsumer {

    private static final Logger log = LoggerFactory.getLogger(InsightSinkConsumer.class);

    private final PostgresTripSink postgresTripSink;
    private final PostgresAlertSink postgresAlertSink;
    private final MongoInsightSink mongoInsightSink;
    private final RedisAlertStreamSink redisAlertStreamSink;
    private final ClickHouseFactSink clickHouseFactSink;

    private final Schema tripSchema;
    private final Schema alertSchema;
    private final Schema idleSchema;
    private final Schema chargingSchema;
    private final Schema safetySchema;

    public InsightSinkConsumer(
        PostgresTripSink postgresTripSink,
        PostgresAlertSink postgresAlertSink,
        MongoInsightSink mongoInsightSink,
        RedisAlertStreamSink redisAlertStreamSink,
        ClickHouseFactSink clickHouseFactSink
    ) {
        this.postgresTripSink = postgresTripSink;
        this.postgresAlertSink = postgresAlertSink;
        this.mongoInsightSink = mongoInsightSink;
        this.redisAlertStreamSink = redisAlertStreamSink;
        this.clickHouseFactSink = clickHouseFactSink;

        this.tripSchema = loadSchema("trip_event.avsc");
        this.alertSchema = loadSchema("alert.avsc");
        this.idleSchema = loadSchema("idle_event.avsc");
        this.chargingSchema = loadSchema("charging_session.avsc");
        this.safetySchema = loadSchema("safety_event.avsc");
    }

    @KafkaListener(topics = "${fleetpulse.sink.topics.trips:events.trip.v1}")
    public void onTripRecord(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            TripEventRecord trip = TripEventRecord.fromAvroBinary(record.value(), tripSchema);
            postgresTripSink.upsertTrip(trip);
            clickHouseFactSink.insertTripFact(trip);
            if (ack != null) ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process trip record at offset {}: {}", record.offset(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(topics = "${fleetpulse.sink.topics.alerts:events.alerts.v1}")
    public void onAlertRecord(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            AlertEventRecord alert = AlertEventRecord.fromAvroBinary(record.value(), alertSchema);
            postgresAlertSink.insertAlert(alert);
            mongoInsightSink.upsertAlertInsight(alert);
            redisAlertStreamSink.publishAlert(alert);
            if (ack != null) ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process alert record at offset {}: {}", record.offset(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(topics = "${fleetpulse.sink.topics.idle:events.idle.v1}")
    public void onIdleRecord(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            IdleEventRecord idle = IdleEventRecord.fromAvroBinary(record.value(), idleSchema);
            clickHouseFactSink.insertIdleEpisode(idle);
            if (ack != null) ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process idle record at offset {}: {}", record.offset(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(topics = "${fleetpulse.sink.topics.charging:events.charging.v1}")
    public void onChargingRecord(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            ChargingSessionRecord session = ChargingSessionRecord.fromAvroBinary(record.value(), chargingSchema);
            clickHouseFactSink.insertChargingSession(session);
            if (ack != null) ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process charging record at offset {}: {}", record.offset(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(topics = "${fleetpulse.sink.topics.safety:events.safety.v1}")
    public void onSafetyRecord(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            SafetyEventRecord safety = SafetyEventRecord.fromAvroBinary(record.value(), safetySchema);
            clickHouseFactSink.insertSafetyEvent(safety);
            if (ack != null) ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process safety record at offset {}: {}", record.offset(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Schema getTripSchema() { return tripSchema; }
    public Schema getAlertSchema() { return alertSchema; }
    public Schema getIdleSchema() { return idleSchema; }
    public Schema getChargingSchema() { return chargingSchema; }
    public Schema getSafetySchema() { return safetySchema; }

    private Schema loadSchema(String filename) {
        try {
            ClassPathResource resource = new ClassPathResource("schemas/" + filename);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return new Schema.Parser().parse(is);
                }
            }

            Path path = Paths.get("../../libs/schemas/avro/" + filename);
            if (Files.exists(path)) {
                return new Schema.Parser().parse(Files.newInputStream(path));
            }

            Path pathLocal = Paths.get("libs/schemas/avro/" + filename);
            if (Files.exists(pathLocal)) {
                return new Schema.Parser().parse(Files.newInputStream(pathLocal));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Avro schema " + filename + ": " + e.getMessage(), e);
        }
        throw new IllegalStateException("Schema " + filename + " not found in classpath or filesystem");
    }
}
