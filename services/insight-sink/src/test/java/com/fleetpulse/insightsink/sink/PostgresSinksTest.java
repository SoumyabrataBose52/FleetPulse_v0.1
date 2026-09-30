package com.fleetpulse.insightsink.sink;

import com.fleetpulse.insightsink.model.AlertEventRecord;
import com.fleetpulse.insightsink.model.TripEventRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PostgresSinksTest {

    private JdbcTemplate jdbcTemplate;
    private PostgresTripSink tripSink;
    private PostgresAlertSink alertSink;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        tripSink = new PostgresTripSink(jdbcTemplate, new SimpleMeterRegistry());
        alertSink = new PostgresAlertSink(jdbcTemplate, new SimpleMeterRegistry());
    }

    @Test
    void testTripUpsertExecutesConflictUpdate() {
        TripEventRecord trip = new TripEventRecord(
            "trip-1", "veh-1", 1, 10, 1000L, 2000L,
            0, 0, "gh1", 0, 0, "gh2", 10.0, 1000, 50,
            2.5, null, 1.25, 0.0, "COMPLETED"
        );

        tripSink.upsertTrip(trip);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).update(
            sqlCaptor.capture(),
            any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(),
            any(), any(), any()
        );

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO trip"));
        assertTrue(sql.contains("ON CONFLICT (trip_id, start_ts) DO UPDATE SET"));
    }

    @Test
    void testAlertInsertExecutesConflictDoNothing() {
        AlertEventRecord alert = new AlertEventRecord(
            "alert-1", "alert-key-1", 1, "veh-1", "CRITICAL_DTC", "CRITICAL",
            1000L, "Engine misfire", Map.of("dtc", "P0300"), "mongo-ref-1"
        );

        alertSink.insertAlert(alert);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).update(
            sqlCaptor.capture(),
            any(), any(), any(), any(), any(),
            any(), any(), any()
        );

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO alert"));
        assertTrue(sql.contains("ON CONFLICT (alert_key) DO NOTHING"));
    }
}
