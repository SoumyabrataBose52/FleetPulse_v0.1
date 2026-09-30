package com.fleetpulse.insightsink.sink;

import com.fleetpulse.insightsink.model.ChargingSessionRecord;
import com.fleetpulse.insightsink.model.IdleEventRecord;
import com.fleetpulse.insightsink.model.SafetyEventRecord;
import com.fleetpulse.insightsink.model.TripEventRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * ClickHouse sink for domain fact tables: trip_fact, idle_episode, charging_session, safety_event (§4.5, §5.2).
 */
@Repository
public class ClickHouseFactSink {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseFactSink.class);

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneOffset.UTC);

    private final String clickhouseUrl;
    private final String database;
    private final String user;
    private final String password;
    private final HttpClient httpClient;
    private final Counter factsInsertedTotal;

    public ClickHouseFactSink(
        @Value("${clickhouse.url:http://localhost:8123}") String clickhouseUrl,
        @Value("${clickhouse.database:fleetpulse}") String database,
        @Value("${clickhouse.user:fleetpulse}") String user,
        @Value("${clickhouse.password:fleetpulse_dev}") String password,
        MeterRegistry registry
    ) {
        this.clickhouseUrl = clickhouseUrl.endsWith("/") ? clickhouseUrl.substring(0, clickhouseUrl.length() - 1) : clickhouseUrl;
        this.database = database;
        this.user = user;
        this.password = password;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
        this.factsInsertedTotal = registry.counter("fleetpulse.clickhouse.facts.inserted.total");
    }

    public void insertTripFact(TripEventRecord trip) {
        if (trip == null) return;

        String query = String.format("INSERT INTO %s.trip_fact (" +
            "trip_id, vehicle_pid, tenant_id, driver_id, start_ts, end_ts, " +
            "start_geohash7, end_geohash7, distance_km, duration_s, idle_s, " +
            "energy_kwh, fuel_l, cost) FORMAT TabSeparated", database);

        StringBuilder sb = new StringBuilder();
        sb.append(trip.tripId()).append('\t')
            .append(trip.vehiclePid()).append('\t')
            .append(trip.tenantId()).append('\t')
            .append(trip.driverId() != null ? trip.driverId() : "\\N").append('\t')
            .append(formatTs(trip.startTs())).append('\t')
            .append(formatTs(trip.endTs())).append('\t')
            .append(trip.startGeohash7()).append('\t')
            .append(trip.endGeohash7()).append('\t')
            .append(trip.distanceKm()).append('\t')
            .append(trip.durationS()).append('\t')
            .append(trip.idleS()).append('\t')
            .append(trip.energyKwh() != null ? trip.energyKwh() : "\\N").append('\t')
            .append(trip.fuelL() != null ? trip.fuelL() : "\\N").append('\t')
            .append(trip.cost()).append('\n');

        executeInsert(query, sb.toString());
        factsInsertedTotal.increment();
    }

    public void insertIdleEpisode(IdleEventRecord idle) {
        if (idle == null) return;

        String query = String.format("INSERT INTO %s.idle_episode (" +
            "episode_id, vehicle_pid, tenant_id, trip_id, start_ts, end_ts, " +
            "duration_s, geohash7, fuel_burn_l, cost, co2_kg, avoidable_cost, powertrain) FORMAT TabSeparated", database);

        StringBuilder sb = new StringBuilder();
        sb.append(idle.idleId()).append('\t')
            .append(idle.vehiclePid()).append('\t')
            .append(idle.tenantId()).append('\t')
            .append("\\N").append('\t') // trip_id
            .append(formatTs(idle.startTs())).append('\t')
            .append(formatTs(idle.endTs())).append('\t')
            .append(idle.durationS()).append('\t')
            .append(idle.geohash7()).append('\t')
            .append(idle.fuelBurnL()).append('\t')
            .append(idle.cost()).append('\t')
            .append(idle.co2Kg()).append('\t')
            .append(idle.avoidableCost()).append('\t')
            .append(idle.powertrain()).append('\n');

        executeInsert(query, sb.toString());
        factsInsertedTotal.increment();
    }

    public void insertChargingSession(ChargingSessionRecord session) {
        if (session == null) return;

        String query = String.format("INSERT INTO %s.charging_session (" +
            "session_id, vehicle_pid, tenant_id, charger_id, plug_in_ts, plug_out_ts, " +
            "soc_start_pct, soc_end_pct, energy_kwh, avg_kw, price_paid, baseline_cost, smart_cost, soh_estimate) FORMAT TabSeparated", database);

        StringBuilder sb = new StringBuilder();
        sb.append(session.sessionId()).append('\t')
            .append(session.vehiclePid()).append('\t')
            .append(session.tenantId()).append('\t')
            .append(session.chargerId() != null ? session.chargerId() : "\\N").append('\t')
            .append(formatTs(session.startTs())).append('\t')
            .append(formatTs(session.endTs())).append('\t')
            .append(session.startSocPct()).append('\t')
            .append(session.endSocPct()).append('\t')
            .append(session.energyKwh()).append('\t')
            .append(session.avgKw()).append('\t')
            .append(session.pricePaid()).append('\t')
            .append(session.baselineCost()).append('\t')
            .append(session.smartCost()).append('\t')
            .append(session.sohEstimate() != null ? session.sohEstimate() : "\\N").append('\n');

        executeInsert(query, sb.toString());
        factsInsertedTotal.increment();
    }

    public void insertSafetyEvent(SafetyEventRecord safety) {
        if (safety == null) return;

        String query = String.format("INSERT INTO %s.safety_event (" +
            "event_id, vehicle_pid, tenant_id, driver_id, ts, event_type, " +
            "accel_ms2, speed_kmh, speed_limit_kmh, weight) FORMAT TabSeparated", database);

        long eventId = 0L;
        try {
            eventId = Long.parseUnsignedLong(safety.eventId(), 16);
        } catch (Exception e) {
            eventId = (long) safety.eventId().hashCode();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(Long.toUnsignedString(eventId)).append('\t')
            .append(safety.vehiclePid()).append('\t')
            .append(safety.tenantId()).append('\t')
            .append(safety.driverId() != null ? safety.driverId() : "\\N").append('\t')
            .append(formatTs(safety.ts())).append('\t')
            .append(safety.eventType()).append('\t')
            .append(safety.severity()).append('\t')
            .append(safety.speedKmh()).append('\t')
            .append("\\N").append('\t') // speed_limit_kmh
            .append(1.0f).append('\n'); // weight

        executeInsert(query, sb.toString());
        factsInsertedTotal.increment();
    }

    private void executeInsert(String query, String tsvBody) {
        try {
            String uriStr = clickhouseUrl + "/?database=" + URLEncoder.encode(database, StandardCharsets.UTF_8)
                + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

            HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(uriStr))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "text/tab-separated-values; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(tsvBody, StandardCharsets.UTF_8));

            if (user != null && !user.isBlank()) {
                req.header("X-ClickHouse-User", user);
                if (password != null) {
                    req.header("X-ClickHouse-Key", password);
                }
            }

            HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("ClickHouse fact insert failed HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Error connecting to ClickHouse for fact insert: {}", e.getMessage());
        }
    }

    private String formatTs(long epochMs) {
        return TS_FORMATTER.format(Instant.ofEpochMilli(epochMs));
    }
}
