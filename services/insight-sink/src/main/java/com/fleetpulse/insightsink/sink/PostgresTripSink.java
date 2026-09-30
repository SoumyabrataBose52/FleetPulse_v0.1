package com.fleetpulse.insightsink.sink;

import com.fleetpulse.insightsink.model.TripEventRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Idempotent PostgreSQL sink for completed trip records (§4.5, §5.3, §8.M2).
 */
@Repository
public class PostgresTripSink {

    private static final Logger log = LoggerFactory.getLogger(PostgresTripSink.class);

    private static final String UPSERT_TRIP_SQL = """
        INSERT INTO trip (
            trip_id, vehicle_pid, tenant_id, start_ts, end_ts,
            start_geohash7, end_geohash7, distance_km, duration_s, idle_s,
            energy_kwh, fuel_l, cost
        ) VALUES (
            ?::uuid, ?::uuid, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?
        ) ON CONFLICT (trip_id, start_ts) DO UPDATE SET
            end_ts = EXCLUDED.end_ts,
            end_geohash7 = EXCLUDED.end_geohash7,
            distance_km = EXCLUDED.distance_km,
            duration_s = EXCLUDED.duration_s,
            idle_s = EXCLUDED.idle_s,
            energy_kwh = EXCLUDED.energy_kwh,
            fuel_l = EXCLUDED.fuel_l,
            cost = EXCLUDED.cost
        """;

    private final JdbcTemplate jdbcTemplate;
    private final Counter tripUpsertsTotal;

    public PostgresTripSink(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.tripUpsertsTotal = registry.counter("fleetpulse.postgres.trip.upserts.total");
    }

    public void upsertTrip(TripEventRecord trip) {
        if (trip == null) return;

        jdbcTemplate.update(
            UPSERT_TRIP_SQL,
            trip.tripId(),
            trip.vehiclePid(),
            trip.tenantId(),
            Timestamp.from(Instant.ofEpochMilli(trip.startTs())),
            Timestamp.from(Instant.ofEpochMilli(trip.endTs())),
            trip.startGeohash7(),
            trip.endGeohash7(),
            trip.distanceKm(),
            trip.durationS(),
            trip.idleS(),
            trip.energyKwh(),
            trip.fuelL(),
            trip.cost()
        );

        tripUpsertsTotal.increment();
        log.debug("Successfully upserted trip: {} for vehicle: {}", trip.tripId(), trip.vehiclePid());
    }
}
