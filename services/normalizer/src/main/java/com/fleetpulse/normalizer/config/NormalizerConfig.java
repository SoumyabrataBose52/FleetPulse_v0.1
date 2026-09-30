package com.fleetpulse.normalizer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fleetpulse.normalizer.mapping.MappingCompiler;
import com.fleetpulse.normalizer.mapping.MappingRegistry;
import com.fleetpulse.normalizer.registry.VehicleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * §4.5 & §15.6 Spring configuration and bootstrap initializer.
 */
@Configuration
public class NormalizerConfig {

    private static final Logger log = LoggerFactory.getLogger(NormalizerConfig.class);

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Bean
    public MappingCompiler mappingCompiler(ObjectMapper objectMapper) {
        return new MappingCompiler(objectMapper);
    }

    @Bean
    public ApplicationRunner initData(
        MappingRegistry mappingRegistry,
        VehicleRegistry vehicleRegistry,
        NormalizerProperties properties
    ) {
        return args -> {
            log.info("Bootstrapping Normalizer data structures...");

            // 1. Load OEM mappings
            Path mappingsPath = Paths.get(properties.mappingsDir());
            int loadedMappings = mappingRegistry.loadFromDirectory(mappingsPath);
            if (loadedMappings == 0) {
                // Try fallback relative path
                mappingRegistry.loadFromDirectory(Paths.get("config/oem-mappings"));
            }

            // 2. Load vehicle seed inventory
            Path seedPath = Paths.get(properties.seedVehiclesPath());
            int loadedVehicles = vehicleRegistry.loadFromCsv(seedPath);
            if (loadedVehicles == 0) {
                // Try fallback relative path
                vehicleRegistry.loadFromCsv(Paths.get("data/seed/vehicles.csv"));
            }

            log.info("Normalizer initialization complete: {} active mappings, {} cached vehicles",
                mappingRegistry.getAllActive().size(), vehicleRegistry.size());
        };
    }
}
