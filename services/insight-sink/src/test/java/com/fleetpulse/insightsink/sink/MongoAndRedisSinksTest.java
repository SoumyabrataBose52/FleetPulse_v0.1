package com.fleetpulse.insightsink.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpulse.insightsink.model.AlertEventRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MongoAndRedisSinksTest {

    private MongoTemplate mongoTemplate;
    private MongoInsightSink mongoSink;

    private StringRedisTemplate redisTemplate;
    private RedisAlertStreamSink redisSink;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        mongoSink = new MongoInsightSink(mongoTemplate, new SimpleMeterRegistry());

        redisTemplate = mock(StringRedisTemplate.class);
        redisSink = new RedisAlertStreamSink(redisTemplate, new ObjectMapper(), new SimpleMeterRegistry());
    }

    @Test
    void testMongoUpsertKeyedByAlertKey() {
        AlertEventRecord alert = new AlertEventRecord(
            "alert-1", "alert-key-1", 1, "veh-1", "CRITICAL_DTC", "CRITICAL",
            1000L, "Engine misfire", Map.of("dtc", "P0300"), "mongo-ref-1"
        );

        mongoSink.upsertAlertInsight(alert);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);

        verify(mongoTemplate, times(1)).upsert(queryCaptor.capture(), updateCaptor.capture(), eq("insights"));

        assertEquals("alert-key-1", queryCaptor.getValue().getQueryObject().get("alert_key"));
    }

    @Test
    void testRedisStreamAppendAndPubSubPublish() {
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);

        AlertEventRecord alert = new AlertEventRecord(
            "alert-1", "alert-key-1", 42, "veh-1", "RANGE_RISK", "HIGH",
            1000L, "Low battery", Map.of("soc", "15"), null
        );

        redisSink.publishAlert(alert);

        verify(streamOps, times(1)).add(any());
        verify(redisTemplate, times(1)).convertAndSend(eq("channel:alerts:42"), anyString());
    }
}
