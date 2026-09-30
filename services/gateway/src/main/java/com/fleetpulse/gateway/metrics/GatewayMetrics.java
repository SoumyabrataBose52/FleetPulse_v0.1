package com.fleetpulse.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * §12.1 Micrometer Metrics Collector for Gateway Service.
 *
 * Tracks:
 *  - ingest.events.total (tags: oem, status)
 *  - ingest.batches.total
 *  - ingest.bytes.total
 *  - ingest.rejected.429.total
 *  - ingest.queue.depth (gauge)
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;
    private final Counter batchesTotal;
    private final Counter bytesTotal;
    private final Counter rejected429Total;
    private final AtomicInteger queueDepthGauge = new AtomicInteger(0);

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.batchesTotal = Counter.builder("ingest.batches.total")
            .description("Total HTTP batches received")
            .register(registry);
        this.bytesTotal = Counter.builder("ingest.bytes.total")
            .description("Total payload bytes received")
            .register(registry);
        this.rejected429Total = Counter.builder("ingest.rejected.429.total")
            .description("Total requests rejected due to backpressure")
            .register(registry);

        registry.gauge("ingest.queue.depth", queueDepthGauge);
    }

    public void recordBatch(String oem, int eventCount, int bytes) {
        batchesTotal.increment();
        bytesTotal.increment(bytes);
        registry.counter("ingest.events.total", Tags.of("oem", oem, "status", "accepted"))
            .increment(eventCount);
    }

    public void recordRejected429() {
        rejected429Total.increment();
    }

    public void setQueueDepth(int depth) {
        queueDepthGauge.set(depth);
    }
}
