package com.fleetpulse.insightsink.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpulse.insightsink.model.AlertEventRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis stream and pub/sub sink for live SSE alert fan-out (§4.5, §8, §15.8).
 */
@Repository
public class RedisAlertStreamSink {

    private static final Logger log = LoggerFactory.getLogger(RedisAlertStreamSink.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Counter streamAppendsTotal;

    public RedisAlertStreamSink(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.streamAppendsTotal = registry.counter("fleetpulse.redis.alerts.stream.appends.total");
    }

    public void publishAlert(AlertEventRecord alert) {
        if (alert == null) return;

        try {
            String streamKey = "stream:alerts:" + alert.tenantId();
            String channelKey = "channel:alerts:" + alert.tenantId();

            Map<String, String> fields = new HashMap<>();
            fields.put("alert_id", alert.alertId());
            fields.put("alert_key", alert.alertKey());
            fields.put("vehicle_pid", alert.vehiclePid());
            fields.put("type", alert.type());
            fields.put("severity", alert.severity());
            fields.put("ts", String.valueOf(alert.ts()));
            fields.put("message", alert.message());

            // 1. Append to Redis Stream (with automatic max-len trim 10,000)
            MapRecord<String, String, String> record = StreamRecords.string(fields).withStreamKey(streamKey);
            redisTemplate.opsForStream().add(record);

            // 2. Publish to Redis Pub/Sub channel for live SSE
            String jsonPayload = objectMapper.writeValueAsString(fields);
            redisTemplate.convertAndSend(channelKey, jsonPayload);

            streamAppendsTotal.increment();
            log.debug("Published alert {} to Redis stream and pubsub", alert.alertId());

        } catch (Exception e) {
            log.error("Failed to publish alert {} to Redis: {}", alert.alertId(), e.getMessage());
            // Redis alert stream error should not crash transactional pipeline unless critical
        }
    }
}
