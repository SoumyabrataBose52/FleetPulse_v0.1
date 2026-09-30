package com.fleetpulse.streamengine.consumer;

import com.fleetpulse.streamengine.model.*;
import com.fleetpulse.streamengine.processor.*;
import com.fleetpulse.streamengine.producer.StreamEngineProducer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * High-throughput Kafka consumer driving stateful stream processing (§4.5, §15.9, §15.10).
 * Ingests cleaned ordered telemetry and executes trips, idle, EV, safety, geofence, and alert analytics.
 */
@Component
public class StreamEngineConsumer {

    private static final Logger log = LoggerFactory.getLogger(StreamEngineConsumer.class);

    private final TripsProcessor tripsProcessor;
    private final IdleProcessor idleProcessor;
    private final EvProcessor evProcessor;
    private final SafetyProcessor safetyProcessor;
    private final GeofenceProcessor geofenceProcessor;
    private final StreamEngineProducer producer;

    private final Schema canonicalSchema;
    private final Schema tripSchema;
    private final Schema idleSchema;
    private final Schema chargingSchema;
    private final Schema alertSchema;
    private final Schema safetySchema;
    private final Schema geofenceSchema;

    private final Counter processedEventsTotal;
    private final Counter processingErrorsTotal;

    public StreamEngineConsumer(
        TripsProcessor tripsProcessor,
        IdleProcessor idleProcessor,
        EvProcessor evProcessor,
        SafetyProcessor safetyProcessor,
        GeofenceProcessor geofenceProcessor,
        StreamEngineProducer producer,
        MeterRegistry registry
    ) {
        this.tripsProcessor = tripsProcessor;
        this.idleProcessor = idleProcessor;
        this.evProcessor = evProcessor;
        this.safetyProcessor = safetyProcessor;
        this.geofenceProcessor = geofenceProcessor;
        this.producer = producer;

        this.canonicalSchema = loadSchema("canonical_event.avsc");
        this.tripSchema = loadSchema("trip_event.avsc");
        this.idleSchema = loadSchema("idle_event.avsc");
        this.chargingSchema = loadSchema("charging_session.avsc");
        this.alertSchema = loadSchema("alert.avsc");
        this.safetySchema = loadSchema("safety_event.avsc");
        this.geofenceSchema = loadSchema("geofence_event.avsc");

        this.processedEventsTotal = registry.counter("fleetpulse.stream.events.processed.total");
        this.processingErrorsTotal = registry.counter("fleetpulse.stream.events.errors.total");
    }

    @KafkaListener(topics = "${fleetpulse.stream.topics.input:telemetry.clean.v1}")
    public void onTelemetryRecord(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            CanonicalTelemetryEvent event = CanonicalTelemetryEvent.fromAvroBinary(record.value(), canonicalSchema);

            // 1. Process Trips (§7.10, §8.M2)
            tripsProcessor.processEvent(event).ifPresent(trip -> {
                try {
                    byte[] payload = trip.toAvroBinary(tripSchema);
                    producer.sendTrip(event.vehiclePid(), payload);
                } catch (Exception e) {
                    log.error("Failed to serialize trip event: {}", e.getMessage(), e);
                }
            });

            // 2. Process Idle (§7.11, §8.M3)
            idleProcessor.processEvent(event).ifPresent(idle -> {
                try {
                    byte[] payload = idle.toAvroBinary(idleSchema);
                    producer.sendIdle(event.vehiclePid(), payload);
                } catch (Exception e) {
                    log.error("Failed to serialize idle event: {}", e.getMessage(), e);
                }
            });

            // 3. Process EV Charging Session (§7.13, §7.15, §8.M4)
            evProcessor.processChargingSession(event).ifPresent(session -> {
                try {
                    byte[] payload = session.toAvroBinary(chargingSchema);
                    producer.sendCharging(event.vehiclePid(), payload);
                } catch (Exception e) {
                    log.error("Failed to serialize charging session event: {}", e.getMessage(), e);
                }
            });

            // 4. Process Range Risk Alert (§8.M4)
            evProcessor.processRangeRisk(event).ifPresent(alert -> {
                try {
                    byte[] payload = alert.toAvroBinary(alertSchema);
                    producer.sendAlert(event.vehiclePid(), payload);
                } catch (Exception e) {
                    log.error("Failed to serialize range risk alert: {}", e.getMessage(), e);
                }
            });

            // 5. Process Driver Safety Harsh Events (§7.16, §8.S1)
            safetyProcessor.processEvent(event).ifPresent(safety -> {
                try {
                    byte[] payload = safety.toAvroBinary(safetySchema);
                    producer.sendSafety(event.vehiclePid(), payload);
                } catch (Exception e) {
                    log.error("Failed to serialize safety event: {}", e.getMessage(), e);
                }
            });

            // 6. Process Geofences and Tow Anomalies (§7.17, §8.S2)
            GeofenceProcessor.GeofenceResult geoRes = geofenceProcessor.processEvent(event);
            geoRes.geofenceEvent().ifPresent(geo -> {
                try {
                    byte[] payload = geo.toAvroBinary(geofenceSchema);
                    producer.sendGeofence(event.vehiclePid(), payload);
                } catch (Exception e) {
                    log.error("Failed to serialize geofence event: {}", e.getMessage(), e);
                }
            });
            geoRes.alertEvent().ifPresent(alert -> {
                try {
                    byte[] payload = alert.toAvroBinary(alertSchema);
                    producer.sendAlert(event.vehiclePid(), payload);
                } catch (Exception e) {
                    log.error("Failed to serialize geofence alert: {}", e.getMessage(), e);
                }
            });

            processedEventsTotal.increment();
        } catch (Exception e) {
            processingErrorsTotal.increment();
            log.error("Error processing telemetry record key={}: {}", record.key(), e.getMessage(), e);
        } finally {
            if (ack != null) {
                ack.acknowledge();
            }
        }
    }

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

    public Schema getCanonicalSchema() { return canonicalSchema; }
    public Schema getTripSchema() { return tripSchema; }
    public Schema getIdleSchema() { return idleSchema; }
    public Schema getChargingSchema() { return chargingSchema; }
    public Schema getAlertSchema() { return alertSchema; }
    public Schema getSafetySchema() { return safetySchema; }
    public Schema getGeofenceSchema() { return geofenceSchema; }
}
