package com.fleetpulse.gateway.service;

import com.fleetpulse.gateway.config.GatewayProperties;
import com.fleetpulse.gateway.exception.BackpressureException;
import com.fleetpulse.gateway.exception.PrecheckException;
import com.fleetpulse.gateway.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IngestServiceTest {

    private KafkaTemplate<String, byte[]> kafkaTemplate;
    private GatewayProperties properties;
    private KeyExtractor keyExtractor;
    private GatewayMetrics metrics;
    private IngestService ingestService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new GatewayProperties(
            new GatewayProperties.KafkaProps("raw.telemetry"),
            new GatewayProperties.QueueProps(100, 0.80),
            10,
            1024,
            new GatewayProperties.MtlsProps(false, List.of())
        );
        keyExtractor = new KeyExtractor();
        metrics = new GatewayMetrics(new SimpleMeterRegistry());
        ingestService = new IngestService(kafkaTemplate, properties, keyExtractor, metrics);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessBatchSuccess() {
        CompletableFuture<SendResult<String, byte[]>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        String ndjson = "{\"vehicleId\":\"VIN1\",\"speed\":50}\n{\"vehicleId\":\"VIN2\",\"speed\":60}";
        int accepted = ingestService.processBatch(ndjson, "A", "00-trace-01");

        assertEquals(2, accepted);
        assertEquals(2, ingestService.getInFlightCount());

        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(2)).send(captor.capture());

        List<ProducerRecord<String, byte[]>> records = captor.getAllValues();
        assertEquals("raw.telemetry", records.get(0).topic());
        assertEquals("VIN1", records.get(0).key());
        assertEquals("VIN2", records.get(1).key());

        // Verify headers
        String oemHeader = new String(records.get(0).headers().lastHeader("oem").value(), StandardCharsets.UTF_8);
        assertEquals("A", oemHeader);
        String traceHeader = new String(records.get(0).headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
        assertEquals("00-trace-01", traceHeader);

        // Complete the future
        future.complete(null);
        assertEquals(0, ingestService.getInFlightCount());
    }

    @Test
    void testProcessBatchEmptyBodyThrowsPrecheckException() {
        assertThrows(PrecheckException.class, () -> ingestService.processBatch("", "A", null));
        assertThrows(PrecheckException.class, () -> ingestService.processBatch("   \n   ", "A", null));
    }

    @Test
    void testProcessBatchExceedsMaxPayloadBytesThrowsPrecheckException() {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            large.append("x");
        }
        assertThrows(PrecheckException.class, () -> ingestService.processBatch(large.toString(), "A", null));
    }

    @Test
    void testProcessBatchExceedsMaxBatchSizeThrowsPrecheckException() {
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            batch.append("{\"vehicleId\":\"VIN").append(i).append("\"}\n");
        }
        assertThrows(PrecheckException.class, () -> ingestService.processBatch(batch.toString(), "A", null));
    }

    @Test
    void testProcessBatchBackpressureThrows429Exception() {
        // High watermark is 100 * 0.80 = 80
        ingestService.setInFlightCountForTesting(80);

        String ndjson = "{\"vehicleId\":\"VIN1\",\"speed\":50}";
        BackpressureException ex = assertThrows(BackpressureException.class, () ->
            ingestService.processBatch(ndjson, "A", null)
        );
        assertTrue(ex.getMessage().contains("exceeds high watermark limit"));
    }
}
