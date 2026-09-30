package com.fleetpulse.insightsink.consumer;

import com.fleetpulse.insightsink.model.AlertEventRecord;
import com.fleetpulse.insightsink.model.TripEventRecord;
import com.fleetpulse.insightsink.sink.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InsightSinkConsumerTest {

    private PostgresTripSink postgresTripSink;
    private PostgresAlertSink postgresAlertSink;
    private MongoInsightSink mongoInsightSink;
    private RedisAlertStreamSink redisAlertStreamSink;
    private ClickHouseFactSink clickHouseFactSink;
    private InsightSinkConsumer consumer;

    @BeforeEach
    void setUp() {
        postgresTripSink = mock(PostgresTripSink.class);
        postgresAlertSink = mock(PostgresAlertSink.class);
        mongoInsightSink = mock(MongoInsightSink.class);
        redisAlertStreamSink = mock(RedisAlertStreamSink.class);
        clickHouseFactSink = mock(ClickHouseFactSink.class);

        consumer = new InsightSinkConsumer(
            postgresTripSink,
            postgresAlertSink,
            mongoInsightSink,
            redisAlertStreamSink,
            clickHouseFactSink
        );
    }

    @Test
    void testOnTripRecordDispatchesToPostgresAndClickHouse() throws Exception {
        TripEventRecord trip = new TripEventRecord(
            "trip-1", "veh-1", 1, 10, 1000L, 2000L,
            0, 0, "gh1", 0, 0, "gh2", 10.0, 1000, 50,
            2.5, null, 1.25, 0.0, "COMPLETED"
        );
        byte[] bytes = trip.toAvroBinary(consumer.getTripSchema());
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("events.trip.v1", 0, 1L, "k", bytes);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onTripRecord(record, ack);

        verify(postgresTripSink, times(1)).upsertTrip(any());
        verify(clickHouseFactSink, times(1)).insertTripFact(any());
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void testOnAlertRecordDispatchesToPostgresMongoAndRedis() throws Exception {
        AlertEventRecord alert = new AlertEventRecord(
            "alert-1", "alert-key-1", 1, "veh-1", "CRITICAL_DTC", "CRITICAL",
            1000L, "Engine misfire", Map.of("dtc", "P0300"), "mongo-ref-1"
        );
        byte[] bytes = alert.toAvroBinary(consumer.getAlertSchema());
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("events.alerts.v1", 0, 1L, "k", bytes);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onAlertRecord(record, ack);

        verify(postgresAlertSink, times(1)).insertAlert(any());
        verify(mongoInsightSink, times(1)).upsertAlertInsight(any());
        verify(redisAlertStreamSink, times(1)).publishAlert(any());
        verify(ack, times(1)).acknowledge();
    }
}
