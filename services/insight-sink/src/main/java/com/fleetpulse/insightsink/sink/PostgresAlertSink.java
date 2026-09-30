package com.fleetpulse.insightsink.sink;

import com.fleetpulse.insightsink.model.AlertEventRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Idempotent PostgreSQL sink for operational alerts (§4.5, §5.3).
 */
@Repository
public class PostgresAlertSink {

    private static final Logger log = LoggerFactory.getLogger(PostgresAlertSink.class);

    private static final String INSERT_ALERT_SQL = """
        INSERT INTO alert (
            alert_id, alert_key, tenant_id, vehicle_pid, type,
            severity, status, opened_at, insight_ref
        ) VALUES (
            ?::uuid, ?, ?, ?::uuid, ?,
            ?::alert_severity, 'OPEN'::alert_status, ?, ?
        ) ON CONFLICT (alert_key) DO NOTHING
        """;

    private final JdbcTemplate jdbcTemplate;
    private final Counter alertInsertsTotal;

    public PostgresAlertSink(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.alertInsertsTotal = registry.counter("fleetpulse.postgres.alert.inserts.total");
    }

    public void insertAlert(AlertEventRecord alert) {
        if (alert == null) return;

        jdbcTemplate.update(
            INSERT_ALERT_SQL,
            alert.alertId(),
            alert.alertKey(),
            alert.tenantId(),
            alert.vehiclePid(),
            alert.type(),
            alert.severity(),
            Timestamp.from(Instant.ofEpochMilli(alert.ts())),
            alert.insightRef()
        );

        alertInsertsTotal.increment();
        log.debug("Successfully inserted alert: {} (key: {})", alert.alertId(), alert.alertKey());
    }
}
