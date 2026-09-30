package com.fleetpulse.gateway.controller;

import com.fleetpulse.gateway.config.GatewayProperties;
import com.fleetpulse.gateway.service.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {

    private MockMvc mockMvc;
    private IngestService ingestService;
    private GatewayProperties properties;

    @BeforeEach
    void setUp() {
        ingestService = Mockito.mock(IngestService.class);
        properties = new GatewayProperties(
            new GatewayProperties.KafkaProps("raw.telemetry"),
            new GatewayProperties.QueueProps(1000, 0.85),
            100,
            5242880,
            new GatewayProperties.MtlsProps(false, List.of("A", "B", "C", "D", "E"))
        );
        HealthController controller = new HealthController(ingestService, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testHealthzReturns200Up() throws Exception {
        mockMvc.perform(get("/healthz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void testReadyzReturns200ReadyWhenQueueCapacityAvailable() throws Exception {
        when(ingestService.getInFlightCount()).thenReturn(50);

        mockMvc.perform(get("/readyz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.in_flight").value(50));
    }

    @Test
    void testReadyzReturns503WhenQueueSaturated() throws Exception {
        when(ingestService.getInFlightCount()).thenReturn(1000);

        mockMvc.perform(get("/readyz"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
