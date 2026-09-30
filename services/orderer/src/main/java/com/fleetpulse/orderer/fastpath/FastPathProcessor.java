package com.fleetpulse.orderer.fastpath;

import com.fleetpulse.orderer.model.CanonicalTelemetryEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * §8.M1 Fast-Path Processor.
 *
 * Processes canonical events immediately without waiting for the reorder buffer.
 * Updates Redis live state, manages spatial cell transitions, and evaluates critical rules.
 */
@Component
public class FastPathProcessor {

    private static final Logger log = LoggerFactory.getLogger(FastPathProcessor.class);

    private static final Set<String> CRITICAL_DTC_PREFIXES = Set.of("P03", "P02", "C00");

    private final StringRedisTemplate redisTemplate;
    private final Cache<String, VehicleLiveLocal> localState;
    private final Counter liveUpdatesCounter;
    private final Counter alertsCounter;

    public record VehicleLiveLocal(long lastTs, String geohash6, String status) {}

    @Autowired
    public FastPathProcessor(StringRedisTemplate redisTemplate, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.localState = Caffeine.newBuilder()
            .maximumSize(150_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

        this.liveUpdatesCounter = Counter.builder("fp_fastpath_live_updates_total")
            .description("Total fast-path vehicle live state updates")
            .register(registry);

        this.alertsCounter = Counter.builder("fp_fastpath_alerts_total")
            .description("Total fast-path critical alerts triggered")
            .register(registry);
    }

    public FastPathProcessor() {
        this(null, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    public void process(CanonicalTelemetryEvent event) {
        String pid = event.vehiclePid();
        int tenant = event.tenantId();

        // 1. Stale-drop: last-write-wins by event timestamp (§8.M1)
        VehicleLiveLocal current = localState.getIfPresent(pid);
        if (current != null && event.tsEvent() <= current.lastTs()) {
            return; // Stale position ignored
        }

        // 2. Derive vehicle status (§8.M1)
        String status = deriveStatus(event);

        // 3. Compute Geohashes (p = 3..7)
        String gh6 = null;
        String oldGh6 = current != null ? current.geohash6() : null;

        if (event.latE6() != null && event.lonE6() != null) {
            double lat = event.latE6() / 1_000_000.0;
            double lon = event.lonE6() / 1_000_000.0;
            gh6 = Geohash.encode(lat, lon, 6);
        }

        localState.put(pid, new VehicleLiveLocal(event.tsEvent(), gh6, status));
        liveUpdatesCounter.increment();

        // 4. Redis Updates (if Redis template is configured)
        if (redisTemplate != null) {
            try {
                updateRedisLiveState(tenant, pid, event, status);

                if (gh6 != null && !gh6.equals(oldGh6)) {
                    updateSpatialCells(tenant, pid, oldGh6, gh6);
                }

                // 5. Evaluate critical stateless rules (§8.M1)
                evaluateCriticalRules(tenant, pid, event);
            } catch (Exception e) {
                log.warn("Redis live state update failed: {}", e.getMessage());
            }
        }
    }

    private String deriveStatus(CanonicalTelemetryEvent event) {
        if ("CHARGING".equalsIgnoreCase(event.chargeState())) {
            return "CHARGING";
        }
        if (Boolean.FALSE.equals(event.ignition())) {
            return "PARKED";
        }
        if (Boolean.TRUE.equals(event.ignition()) && event.speedKmh() != null && event.speedKmh() >= 5.0f) {
            return "DRIVING";
        }
        return "IDLE";
    }

    private void updateRedisLiveState(int tenant, String pid, CanonicalTelemetryEvent event, String status) {
        String key = "live:" + tenant + ":" + pid;
        Map<String, String> fields = new HashMap<>();
        if (event.latE6() != null) fields.put("lat_e6", String.valueOf(event.latE6()));
        if (event.lonE6() != null) fields.put("lon_e6", String.valueOf(event.lonE6()));
        if (event.speedKmh() != null) fields.put("speed", String.format(java.util.Locale.US, "%.1f", event.speedKmh()));
        if (event.headingDeg() != null) fields.put("hdg", String.valueOf(event.headingDeg()));
        fields.put("ts", String.valueOf(event.tsEvent()));
        fields.put("status", status);
        if (event.socPct() != null) fields.put("soc", String.format(java.util.Locale.US, "%.1f", event.socPct()));
        if (event.fuelPct() != null) fields.put("fuel", String.format(java.util.Locale.US, "%.1f", event.fuelPct()));
        fields.put("dtc_count", String.valueOf(event.dtc() != null ? event.dtc().size() : 0));

        redisTemplate.opsForHash().putAll(key, fields);
    }

    private void updateSpatialCells(int tenant, String pid, String oldGh6, String newGh6) {
        if (oldGh6 != null) {
            redisTemplate.opsForSet().remove("cell:" + tenant + ":" + oldGh6, pid);
        }
        redisTemplate.opsForSet().add("cell:" + tenant + ":" + newGh6, pid);

        // Update spatial count aggregates (gcnt:{tenant}:{p}) for p = 3..7
        for (int p = 3; p <= 7; p++) {
            if (oldGh6 != null && oldGh6.length() >= p) {
                String oldPrefix = oldGh6.substring(0, p);
                redisTemplate.opsForHash().increment("gcnt:" + tenant + ":" + p, oldPrefix, -1);
            }
            if (newGh6.length() >= p) {
                String newPrefix = newGh6.substring(0, p);
                redisTemplate.opsForHash().increment("gcnt:" + tenant + ":" + p, newPrefix, 1);
            }
        }
    }

    private void evaluateCriticalRules(int tenant, String pid, CanonicalTelemetryEvent event) {
        if (event.dtc() != null) {
            for (String code : event.dtc()) {
                if (code.length() >= 3 && CRITICAL_DTC_PREFIXES.contains(code.substring(0, 3))) {
                    raiseCriticalAlert(tenant, pid, "CRITICAL_DTC", "Critical Diagnostic Code detected: " + code);
                    break;
                }
            }
        }
    }

    private void raiseCriticalAlert(int tenant, String pid, String type, String details) {
        String lockKey = "alertcool:" + pid + ":" + type;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(300));
        if (Boolean.TRUE.equals(acquired)) {
            alertsCounter.increment();
            log.warn("RAISED CRITICAL ALERT for vehicle {} [tenant {}]: {} - {}", pid, tenant, type, details);
            // XADD to alerts:{tenant}
            redisTemplate.opsForStream().add("alerts:" + tenant, Map.of(
                "pid", pid,
                "type", type,
                "details", details,
                "timestamp", String.valueOf(System.currentTimeMillis())
            ));
        }
    }

    public VehicleLiveLocal getLocalState(String pid) {
        return localState.getIfPresent(pid);
    }
}
