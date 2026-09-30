package com.fleetpulse.normalizer.registry;

import com.fleetpulse.normalizer.model.VehicleMetadata;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * §4.3 & §5.3 In-Memory High-Speed Vehicle Identity Cache.
 *
 * Resolves VIN to vehicle_pid and tenant_id using Caffeine LRU cache.
 * Strips raw VIN before downstream canonical emission to protect privacy.
 */
@Component
public class VehicleRegistry {

    private static final Logger log = LoggerFactory.getLogger(VehicleRegistry.class);

    private final Cache<String, VehicleMetadata> cache;

    public VehicleRegistry() {
        this.cache = Caffeine.newBuilder()
            .maximumSize(150_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();
    }

    public void register(VehicleMetadata metadata) {
        if (metadata != null && metadata.vin() != null) {
            cache.put(metadata.vin().trim().toUpperCase(), metadata);
        }
    }

    public Optional<VehicleMetadata> resolve(String vin) {
        if (vin == null) {
            return Optional.empty();
        }
        VehicleMetadata meta = cache.getIfPresent(vin.trim().toUpperCase());
        return Optional.ofNullable(meta);
    }

    public int loadFromCsv(Path csvPath) {
        if (csvPath == null || !Files.exists(csvPath)) {
            log.warn("Vehicle seed CSV not found at: {}", csvPath);
            return 0;
        }

        int loaded = 0;
        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String line = reader.readLine(); // Header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 5) {
                    String pid = parts[0].trim();
                    String vin = parts[1].trim();
                    int fleetId = Integer.parseInt(parts[2].trim());
                    int tenantId = Integer.parseInt(parts[3].trim());
                    int modelId = Integer.parseInt(parts[4].trim());

                    cache.put(vin.toUpperCase(), new VehicleMetadata(pid, tenantId, vin, modelId, fleetId));
                    loaded++;
                }
            }
            log.info("Loaded {} vehicles from seed CSV into memory cache", loaded);
        } catch (IOException | NumberFormatException e) {
            log.error("Failed to load vehicle seed CSV: {}", e.getMessage(), e);
        }
        return loaded;
    }

    public long size() {
        return cache.estimatedSize();
    }
}
