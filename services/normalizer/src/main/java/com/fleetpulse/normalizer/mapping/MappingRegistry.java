package com.fleetpulse.normalizer.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * §4.5 & §15.6 Hot-reloadable Thread-Safe Mapping Registry.
 *
 * Stores active and shadow mappings, supporting dynamic registration,
 * activation, and shadow evaluation with zero service restarts.
 */
@Component
public class MappingRegistry {

    private static final Logger log = LoggerFactory.getLogger(MappingRegistry.class);

    private final MappingCompiler compiler;
    private final Map<String, CompiledMapping> activeMappings = new ConcurrentHashMap<>();
    private final Map<String, CompiledMapping> shadowMappings = new ConcurrentHashMap<>();
    private final Map<String, Integer> defaultVersionPerOem = new ConcurrentHashMap<>();

    public MappingRegistry(MappingCompiler compiler) {
        this.compiler = compiler;
    }

    public void register(CompiledMapping mapping) {
        String key = mapping.key();
        if ("SHADOW".equalsIgnoreCase(mapping.status())) {
            shadowMappings.put(key, mapping);
            log.info("Registered SHADOW mapping: {}", key);
        } else {
            activeMappings.put(key, mapping);
            // Default version is the latest active version registered
            defaultVersionPerOem.merge(mapping.oem(), mapping.schemaVer(), Math::max);
            log.info("Registered ACTIVE mapping: {}", key);
        }
    }

    public void registerJson(String jsonContent) {
        CompiledMapping mapping = compiler.compile(jsonContent);
        register(mapping);
    }

    public Optional<CompiledMapping> getActive(String oem, Integer schemaVer) {
        if (oem == null) {
            return Optional.empty();
        }
        String normalizedOem = oem.trim().toUpperCase();
        if (schemaVer != null && schemaVer > 0) {
            return Optional.ofNullable(activeMappings.get(normalizedOem + ":" + schemaVer));
        }

        int defaultVer = defaultVersionPerOem.getOrDefault(normalizedOem, 1);
        CompiledMapping mapping = activeMappings.get(normalizedOem + ":" + defaultVer);
        if (mapping == null) {
            mapping = activeMappings.get(normalizedOem + ":1");
        }
        return Optional.ofNullable(mapping);
    }

    public Optional<CompiledMapping> getShadow(String oem, Integer schemaVer) {
        if (oem == null) {
            return Optional.empty();
        }
        String normalizedOem = oem.trim().toUpperCase();
        int version = (schemaVer != null && schemaVer > 0)
            ? schemaVer
            : defaultVersionPerOem.getOrDefault(normalizedOem, 1);

        return Optional.ofNullable(shadowMappings.get(normalizedOem + ":" + version));
    }

    public int loadFromDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            log.warn("Mapping directory does not exist: {}", dir);
            return 0;
        }

        int count = 0;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".json")).toList();
            for (Path file : files) {
                try {
                    String content = Files.readString(file);
                    // Skip schema definitions or golden samples
                    if (file.getFileName().toString().contains("schema") || file.getFileName().toString().contains("golden")) {
                        continue;
                    }
                    registerJson(content);
                    count++;
                } catch (Exception e) {
                    log.error("Failed to load mapping from {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Error listing mapping directory {}: {}", dir, e.getMessage());
        }

        log.info("Loaded {} OEM mappings from {}", count, dir);
        return count;
    }

    public Collection<CompiledMapping> getAllActive() {
        return Collections.unmodifiableCollection(activeMappings.values());
    }

    public Collection<CompiledMapping> getAllShadow() {
        return Collections.unmodifiableCollection(shadowMappings.values());
    }
}
