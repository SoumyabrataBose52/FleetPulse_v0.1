package com.fleetpulse.streamengine;

import com.fleetpulse.streamengine.consumer.StreamEngineConsumer;
import com.fleetpulse.streamengine.model.CanonicalTelemetryEvent;
import com.fleetpulse.streamengine.processor.*;
import com.fleetpulse.streamengine.producer.StreamEngineProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamEngineConsumerTest {

    @Mock
    private TripsProcessor tripsProcessor;

    @Mock
    private IdleProcessor idleProcessor;

    @Mock
    private EvProcessor evProcessor;

    @Mock
    private SafetyProcessor safetyProcessor;

    @Mock
    private GeofenceProcessor geofenceProcessor;

    @Mock
    private StreamEngineProducer producer;

    @Mock
    private Acknowledgment ack;

    private StreamEngineConsumer consumer;

    @BeforeEach
    void setUp() {
        when(geofenceProcessor.processEvent(any()))
            .thenReturn(new GeofenceProcessor.GeofenceResult(Optional.empty(), Optional.empty()));

        consumer = new StreamEngineConsumer(
            tripsProcessor,
            idleProcessor,
            evProcessor,
            safetyProcessor,
            geofenceProcessor,
            producer,
            new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("Consumer deserializes Avro event, dispatches to processors, and acknowledges")
    void testConsumerDispatchesAndAcknowledges() throws IOException {
        CanonicalTelemetryEvent event = new CanonicalTelemetryEvent(
            "evt-12345",
            "veh-test-99",
            1,
            "TESLA",
            1,
            1_700_000_000_000L,
            1_700_000_000_010L,
            100L,
            37_774_929,
            -122_419_416,
            180,
            45.0f,
            15000.0,
            true,
            null,
            75.0f,
            390.0f,
            "NONE",
            0.0f,
            30.0f,
            1200,
            List.of(),
            null,
            0
        );

        byte[] payload = event.toAvroBinary(consumer.getCanonicalSchema());
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>(
            "telemetry.clean.v1",
            0,
            1001L,
            "veh-test-99",
            payload
        );

        consumer.onTelemetryRecord(record, ack);

        verify(tripsProcessor, times(1)).processEvent(any());
        verify(idleProcessor, times(1)).processEvent(any());
        verify(evProcessor, times(1)).processChargingSession(any());
        verify(evProcessor, times(1)).processRangeRisk(any());
        verify(safetyProcessor, times(1)).processEvent(any());
        verify(geofenceProcessor, times(1)).processEvent(any());
        verify(ack, times(1)).acknowledge();
    }
}
