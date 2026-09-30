package com.fleetpulse.telemetrysink.consumer;

import com.fleetpulse.telemetrysink.sink.TelemetrySinkService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TelemetrySinkConsumerTest {

    private TelemetrySinkService sinkService;
    private TelemetrySinkConsumer consumer;

    @BeforeEach
    void setUp() {
        sinkService = mock(TelemetrySinkService.class);
        consumer = new TelemetrySinkConsumer(sinkService);
    }

    @Test
    void testOnBatchSuccessAcknowledgesOffset() {
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("telemetry.clean.v1", 0, 1L, "k", new byte[]{});
        Acknowledgment ack = mock(Acknowledgment.class);

        when(sinkService.processBatch(anyList())).thenReturn(1);

        consumer.onBatch(List.of(record), ack);

        verify(sinkService, times(1)).processBatch(List.of(record));
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void testOnBatchFailureDoesNotAcknowledge() {
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("telemetry.clean.v1", 0, 1L, "k", new byte[]{});
        Acknowledgment ack = mock(Acknowledgment.class);

        when(sinkService.processBatch(anyList())).thenThrow(new RuntimeException("ClickHouse connection timed out"));

        assertThrows(RuntimeException.class, () -> consumer.onBatch(List.of(record), ack));

        verify(sinkService, times(1)).processBatch(List.of(record));
        verify(ack, never()).acknowledge();
    }

    @Test
    void testOnBatchEmptyListHandlesGracefully() {
        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.onBatch(List.of(), ack);
        verify(ack, times(1)).acknowledge();
        verify(sinkService, never()).processBatch(any());
    }
}
