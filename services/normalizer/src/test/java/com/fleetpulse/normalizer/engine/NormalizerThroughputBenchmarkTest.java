package com.fleetpulse.normalizer.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpulse.normalizer.mapping.MappingCompiler;
import com.fleetpulse.normalizer.mapping.MappingRegistry;
import com.fleetpulse.normalizer.model.VehicleMetadata;
import com.fleetpulse.normalizer.registry.VehicleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §15.6 Normalizer Throughput Benchmark.
 *
 * Verifies per-core normalization throughput on mixed OEM payloads.
 */
class NormalizerThroughputBenchmarkTest {

    private NormalizerEngine engine;
    private final List<BenchmarkPayload> payloads = new ArrayList<>();

    record BenchmarkPayload(String raw, String oem, int schemaVer) {}

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MappingCompiler compiler = new MappingCompiler(objectMapper);
        MappingRegistry registry = new MappingRegistry(compiler);

        Path mappingsDir = findDir("config/oem-mappings");
        registry.loadFromDirectory(mappingsDir);

        VehicleRegistry vehicleRegistry = new VehicleRegistry();
        vehicleRegistry.register(new VehicleMetadata(
            "703092ac-8f03-4fc7-af76-0c416776ff24",
            1,
            "1HGCR2F83HA000001",
            5,
            1
        ));

        engine = new NormalizerEngine(registry, vehicleRegistry, objectMapper, false);

        Path goldenPath = mappingsDir.resolve("golden_samples.json");
        JsonNode golden = objectMapper.readTree(Files.readString(goldenPath));

        for (String key : List.of("oem_a", "oem_b", "oem_c", "oem_d", "oem_e_v1", "oem_e_v2")) {
            JsonNode item = golden.get(key);
            JsonNode rawNode = item.get("raw");
            String rawStr = rawNode.isTextual() ? rawNode.asText() : rawNode.toString();
            payloads.add(new BenchmarkPayload(rawStr, item.get("oem").asText(), item.get("schema_ver").asInt()));
        }
    }

    @Test
    void testNormalizationThroughput() {
        int warmupIterations = 5_000;
        int benchmarkIterations = 30_000;
        long now = 1790331305000L;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            BenchmarkPayload p = payloads.get(i % payloads.size());
            engine.normalize(p.raw(), p.oem(), p.schemaVer(), now, Collections.emptyMap());
        }

        // Timed run
        long start = System.nanoTime();
        int successCount = 0;
        for (int i = 0; i < benchmarkIterations; i++) {
            BenchmarkPayload p = payloads.get(i % payloads.size());
            NormalizationResult res = engine.normalize(p.raw(), p.oem(), p.schemaVer(), now, Collections.emptyMap());
            if (res.success()) {
                successCount++;
            }
        }
        long durationNs = System.nanoTime() - start;
        double durationSec = durationNs / 1_000_000_000.0;
        double eventsPerSec = benchmarkIterations / durationSec;

        System.out.printf("Normalizer Benchmark: %d events in %.3f s = %.1f events/s/core%n",
            benchmarkIterations, durationSec, eventsPerSec);

        assertTrue(successCount == benchmarkIterations);
        // Throughput must exceed at least 15,000 events/sec/core
        assertTrue(eventsPerSec > 15_000.0, "Expected > 15K events/s/core, got: " + eventsPerSec);
    }

    private Path findDir(String relative) {
        Path p1 = Paths.get("../../", relative);
        if (Files.exists(p1)) return p1;
        Path p2 = Paths.get(relative);
        if (Files.exists(p2)) return p2;
        throw new IllegalStateException("Directory not found: " + relative);
    }
}
