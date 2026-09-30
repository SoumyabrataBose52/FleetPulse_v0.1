package com.fleetpulse.gateway.controller;

import com.fleetpulse.gateway.config.GatewayProperties;
import com.fleetpulse.gateway.exception.BackpressureException;
import com.fleetpulse.gateway.service.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IngestControllerTest {

    private MockMvc mockMvc;
    private IngestService ingestService;

    @BeforeEach
    void setUp() {
        ingestService = Mockito.mock(IngestService.class);
        GatewayProperties properties = new GatewayProperties(
            new GatewayProperties.KafkaProps("raw.telemetry"),
            new GatewayProperties.QueueProps(1000, 0.85),
            100,
            5242880,
            new GatewayProperties.MtlsProps(false, List.of())
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new IngestController(ingestService, properties))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private MockMvc createMtlsMockMvc(List<String> allowedCns) {
        GatewayProperties properties = new GatewayProperties(
            new GatewayProperties.KafkaProps("raw.telemetry"),
            new GatewayProperties.QueueProps(1000, 0.85),
            100,
            5242880,
            new GatewayProperties.MtlsProps(true, allowedCns)
        );
        return MockMvcBuilders.standaloneSetup(new IngestController(ingestService, properties))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void testIngestBatchSuccessReturns202() throws Exception {
        when(ingestService.processBatch(anyString(), eq("A"), any())).thenReturn(2);

        String ndjson = "{\"vehicleId\":\"VIN1\",\"speed\":50}\n{\"vehicleId\":\"VIN2\",\"speed\":60}";

        mockMvc.perform(post("/v1/ingest/batch")
                .contentType("application/x-ndjson")
                .header("X-OEM", "A")
                .content(ndjson))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.accepted_count").value(2))
            .andExpect(jsonPath("$.rejected_count").value(0));
    }

    @Test
    void testMissingOemHeaderReturns400() throws Exception {
        String ndjson = "{\"vehicleId\":\"VIN1\",\"speed\":50}";

        mockMvc.perform(post("/v1/ingest/batch")
                .contentType("application/x-ndjson")
                .content(ndjson))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Precheck Validation Failed"));
    }

    @Test
    void testInvalidOemHeaderReturns400() throws Exception {
        mockMvc.perform(post("/v1/ingest/batch")
                .header("X-OEM", "UNKNOWN_OEM")
                .content("some body"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testBackpressureReturns429WithRetryAfter() throws Exception {
        when(ingestService.processBatch(anyString(), eq("A"), any()))
            .thenThrow(new BackpressureException("Queue full"));

        mockMvc.perform(post("/v1/ingest/batch")
                .header("X-OEM", "A")
                .content("{\"vehicleId\":\"VIN1\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.title").value("Ingestion Rate Exceeded"));
    }

    @Test
    void testMtlsUnauthorizedClientReturns403() throws Exception {
        MockMvc mtlsMockMvc = createMtlsMockMvc(List.of("oem-client-a"));

        mtlsMockMvc.perform(post("/v1/ingest/batch")
                .header("X-OEM", "A")
                .header("X-Client-Cert", "rogue-attacker")
                .content("{\"vehicleId\":\"VIN1\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title").value("mTLS Client Certificate Rejected"));
    }
}
