package com.fleetpulse.telemetrysink.controller;

import com.fleetpulse.telemetrysink.sink.ClickHouseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {

    private ClickHouseClient clickHouseClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clickHouseClient = mock(ClickHouseClient.class);
        HealthController controller = new HealthController(clickHouseClient);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testHealthzReturnsUp() throws Exception {
        mockMvc.perform(get("/healthz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.service").value("telemetry-sink"));
    }

    @Test
    void testReadyzReturnsUpWhenClickHouseConnected() throws Exception {
        when(clickHouseClient.ping()).thenReturn(true);

        mockMvc.perform(get("/readyz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.clickhouse").value("CONNECTED"));
    }

    @Test
    void testReadyzReturns503WhenClickHouseDown() throws Exception {
        when(clickHouseClient.ping()).thenReturn(false);

        mockMvc.perform(get("/readyz"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"))
            .andExpect(jsonPath("$.clickhouse").value("DISCONNECTED"));
    }
}
