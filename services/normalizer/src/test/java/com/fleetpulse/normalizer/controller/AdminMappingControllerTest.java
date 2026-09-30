package com.fleetpulse.normalizer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpulse.normalizer.mapping.MappingCompiler;
import com.fleetpulse.normalizer.mapping.MappingRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminMappingControllerTest {

    private MockMvc mockMvc;
    private MappingRegistry mappingRegistry;
    private MappingCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new MappingCompiler(new ObjectMapper());
        mappingRegistry = new MappingRegistry(compiler);

        AdminMappingController controller = new AdminMappingController(mappingRegistry, compiler);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testListMappingsInitiallyEmpty() throws Exception {
        mockMvc.perform(get("/admin/oem-mappings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active_count").value(0))
            .andExpect(jsonPath("$.shadow_count").value(0));
    }

    @Test
    void testRegisterNewMappingReturns201Created() throws Exception {
        String newMappingJson = """
            {
              "oem": "OMEGA",
              "schema_ver": 1,
              "format": "json",
              "vin_field": "vin",
              "fields": {
                "ts_event": { "source": "timestamp", "transform": "iso8601_to_ms", "required": true }
              }
            }
            """;

        mockMvc.perform(post("/admin/oem-mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(newMappingJson))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("REGISTERED"))
            .andExpect(jsonPath("$.oem").value("OMEGA"))
            .andExpect(jsonPath("$.rules_count").value(1));

        // Verify it is listed in active mappings
        mockMvc.perform(get("/admin/oem-mappings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active_count").value(1));
    }
}
