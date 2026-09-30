package com.fleetpulse.normalizer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpulse.normalizer.mapping.MappingCompiler;
import com.fleetpulse.normalizer.mapping.MappingRegistry;
import com.fleetpulse.normalizer.registry.VehicleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {

    private MockMvc mockMvc;
    private MappingRegistry mappingRegistry;
    private VehicleRegistry vehicleRegistry;

    @BeforeEach
    void setUp() {
        MappingCompiler compiler = new MappingCompiler(new ObjectMapper());
        mappingRegistry = new MappingRegistry(compiler);
        vehicleRegistry = new VehicleRegistry();

        HealthController controller = new HealthController(mappingRegistry, vehicleRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testHealthzReturns200Up() throws Exception {
        mockMvc.perform(get("/healthz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void testReadyzReturns503WhenNoMappings() throws Exception {
        mockMvc.perform(get("/readyz"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    void testReadyzReturns200WhenMappingsLoaded() throws Exception {
        String testJson = """
            { "oem": "TEST", "schema_ver": 1, "format": "json", "vin_field": "vin", "fields": {} }
            """;
        mappingRegistry.registerJson(testJson);

        mockMvc.perform(get("/readyz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.active_mappings").value(1));
    }
}
