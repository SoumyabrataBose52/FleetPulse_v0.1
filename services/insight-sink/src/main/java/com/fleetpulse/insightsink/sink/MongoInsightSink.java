package com.fleetpulse.insightsink.sink;

import com.fleetpulse.insightsink.model.AlertEventRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Date;

/**
 * Idempotent MongoDB sink for insight evidence and polymorphic alert payloads (§4.5, §5.5).
 */
@Repository
public class MongoInsightSink {

    private static final Logger log = LoggerFactory.getLogger(MongoInsightSink.class);
    private static final String COLLECTION = "insights";

    private final MongoTemplate mongoTemplate;
    private final Counter insightUpsertsTotal;

    public MongoInsightSink(MongoTemplate mongoTemplate, MeterRegistry registry) {
        this.mongoTemplate = mongoTemplate;
        this.insightUpsertsTotal = registry.counter("fleetpulse.mongo.insight.upserts.total");
    }

    public void upsertAlertInsight(AlertEventRecord alert) {
        if (alert == null) return;

        Query query = Query.query(Criteria.where("alert_key").is(alert.alertKey()));
        Update update = new Update()
            .set("alert_id", alert.alertId())
            .set("tenant_id", alert.tenantId())
            .set("vehicle_pid", alert.vehiclePid())
            .set("type", alert.type())
            .set("severity", alert.severity())
            .set("ts", Date.from(Instant.ofEpochMilli(alert.ts())))
            .set("message", alert.message())
            .set("details", alert.details())
            .setOnInsert("created_at", new Date());

        mongoTemplate.upsert(query, update, COLLECTION);
        insightUpsertsTotal.increment();
        log.debug("Upserted MongoDB insight for alert key: {}", alert.alertKey());
    }

    public void upsertGenericInsight(String insightId, int tenantId, String vehiclePid, String type, Document evidence) {
        Query query = Query.query(Criteria.where("insight_id").is(insightId));
        Update update = new Update()
            .set("tenant_id", tenantId)
            .set("vehicle_pid", vehiclePid)
            .set("type", type)
            .set("ts", new Date())
            .set("evidence", evidence)
            .setOnInsert("created_at", new Date());

        mongoTemplate.upsert(query, update, COLLECTION);
        insightUpsertsTotal.increment();
    }
}
