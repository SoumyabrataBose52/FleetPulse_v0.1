package com.fleetpulse.telemetrysink.sink;

import com.fleetpulse.telemetrysink.model.CanonicalTelemetryEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetrySinkServiceTest {

    private ClickHouseClient clickHouseClient;
    private TelemetrySinkService sinkService;

    @BeforeEach
    void setUp() {
        clickHouseClient = mock(ClickHouseClient.class);
        sinkService = new TelemetrySinkService(clickHouseClient, new SimpleMeterRegistry());
    }

    @Test
    void testProcessBatchConstructsDeduplicationToken() throws Exception {
        CanonicalTelemetryEvent event1 = new CanonicalTelemetryEvent(
            "0000000000000001", "pid-1", 1, "A", 1, 1000L, 1000L,
            1L, 0, 0, 0, 0f, 0.0, true, 0f, 0f, 0f, null, 0f, 0f, 0, List.of(), null, 0
        );
        CanonicalTelemetryEvent event2 = new CanonicalTelemetryEvent(
            "0000000000000002", "pid-2", 1, "A", 1, 1001L, 1001L,
            2L, 0, 0, 0, 0f, 0.0, true, 0f, 0f, 0f, null, 0f, 0f, 0, List.of(), null, 0
        );

        byte[] b1 = event1.toAvroBinary(sinkService.getAvroSchema());
        byte[] b2 = event2.toAvroBinary(sinkService.getAvroSchema());

        ConsumerRecord<String, byte[]> rec1 = new ConsumerRecord<>("telemetry.clean.v1", 3, 100L, "k1", b1);
        ConsumerRecord<String, byte[]> rec2 = new ConsumerRecord<>("telemetry.clean.v1", 3, 101L, "k2", b2);

        int inserted = sinkService.processBatch(List.of(rec1, rec2));
        assertEquals(2, inserted);

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CanonicalTelemetryEvent>> eventsCaptor = ArgumentCaptor.forClass(List.class);

        verify(clickHouseClient, times(1)).insertTelemetryBatch(eventsCaptor.capture(), tokenCaptor.capture());

        // Token must follow ADR-004 specification: topic-partition-firstOffset-lastOffset
        assertEquals("telemetry.clean.v1-3-100-101", tokenCaptor.getValue());
        assertEquals(2, eventsCaptor.getValue().size());
    }

    @Test
    void testReplayIsIdempotent() throws Exception {
        // Replaying identical batch must send identical deduplication token to ClickHouse
        CanonicalTelemetryEvent event = new CanonicalTelemetryEvent(
            "0000000000000001", "pid-1", 1, "A", 1, 1000L, 1000L,
            1L, 0, 0, 0, 0f, 0.0, true, 0f, 0f, 0f, null, 0f, 0f, 0, List.of(), null, 0
        );
        byte[] b = event.toAvroBinary(sinkService.getAvroSchema());
        ConsumerRecord<String, byte[]> rec = new ConsumerRecord<>("telemetry.clean.v1", 0, 42L, "k", b);

        sinkService.processBatch(List.of(rec));
        sinkService.processBatch(List.of(rec)); // Replay

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(clickHouseClient, times(2)).insertTelemetryBatch(any(), tokenCaptor.capture());

        List<String> tokens = tokenCaptor.getAllValues();
        assertEquals(2, tokens.size());
        assertEquals(tokens.get(0), tokens.get(1), "Both runs must produce identical deduplication tokens");
        assertEquals("telemetry.clean.v1-0-42-42", tokens.get(0));
    }

    @Test
    void testMultiplePartitionsGroupedSeparately() throws Exception {
        CanonicalTelemetryEvent event1 = new CanonicalTelemetryEvent(
            "0000000000000001", "pid-1", 1, "A", 1, 1000L, 1000L,
            1L, 0, 0, 0, 0f, 0.0, true, 0f, 0f, 0f, null, 0f, 0f, 0, List.of(), null, 0
        );
        byte[] b = event1.toAvroBinary(sinkService.getAvroSchema());

        ConsumerRecord<String, byte[]> recP1 = new ConsumerRecord<>("telemetry.clean.v1", 1, 10L, "k1", b);
        ConsumerRecord<String, byte[]> recP2 = new ConsumerRecord<>("telemetry.clean.v1", 2, 20L, "k2", b);

        int inserted = sinkService.processBatch(List.of(recP1, recP2));
        assertEquals(2, inserted);

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(clickHouseClient, times(2)).insertTelemetryBatch(any(), tokenCaptor.capture());

        List<String> tokens = tokenCaptor.getAllValues();
        assertTrue(tokens.contains("telemetry.clean.v1-1-10-10"));
        assertTrue(tokens.contains("telemetry.clean.v1-2-20-20"));
    }

    @Test
    void testMalformedRecordSkipped() {
        ConsumerRecord<String, byte[]> malformed = new ConsumerRecord<>(
            "telemetry.clean.v1", 0, 1L, "k", new byte[]{1, 2, 3, 4}
        );

        int inserted = sinkService.processBatch(List.of(malformed));
        assertEquals(0, inserted);
        verify(clickHouseClient, never()).insertTelemetryBatch(any(), any());
    }
}
