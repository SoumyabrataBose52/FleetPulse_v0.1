package com.fleetpulse.telemetrysink.sink;

import com.fleetpulse.telemetrysink.model.CanonicalTelemetryEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * ClickHouse HTTP client implementing high-throughput batched ingestion with deduplication tokens (§4.5, §5.2).
 */
@Component
public class HttpClickHouseClient implements ClickHouseClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClickHouseClient.class);

    private final String clickhouseUrl;
    private final String database;
    private final String table;
    private final String user;
    private final String password;
    private final boolean dedupEnabled;
    private final HttpClient httpClient;

    private final Counter insertsTotal;
    private final Counter rowsTotal;
    private final Counter errorsTotal;
    private final Timer insertLatency;

    public HttpClickHouseClient(
        @Value("${clickhouse.url:http://localhost:8123}") String clickhouseUrl,
        @Value("${clickhouse.database:fleetpulse}") String database,
        @Value("${clickhouse.table:telemetry}") String table,
        @Value("${clickhouse.user:fleetpulse}") String user,
        @Value("${clickhouse.password:fleetpulse_dev}") String password,
        @Value("${clickhouse.dedup-enabled:true}") boolean dedupEnabled,
        @Value("${clickhouse.connect-timeout-ms:3000}") int connectTimeoutMs,
        MeterRegistry registry
    ) {
        this.clickhouseUrl = clickhouseUrl.endsWith("/") ? clickhouseUrl.substring(0, clickhouseUrl.length() - 1) : clickhouseUrl;
        this.database = database;
        this.table = table;
        this.user = user;
        this.password = password;
        this.dedupEnabled = dedupEnabled;

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build();

        this.insertsTotal = registry.counter("fleetpulse.clickhouse.inserts.total");
        this.rowsTotal = registry.counter("fleetpulse.clickhouse.rows.total");
        this.errorsTotal = registry.counter("fleetpulse.clickhouse.errors.total");
        this.insertLatency = registry.timer("fleetpulse.clickhouse.insert.latency");
    }

    @Override
    public void insertTelemetryBatch(List<CanonicalTelemetryEvent> events, String deduplicationToken) {
        if (events == null || events.isEmpty()) {
            return;
        }

        String query = String.format(TelemetryBatchFormatter.INSERT_QUERY, database, table);
        String tsvData = TelemetryBatchFormatter.toTabSeparated(events);
        byte[] payload = tsvData.getBytes(StandardCharsets.UTF_8);

        StringBuilder uriBuilder = new StringBuilder(clickhouseUrl)
            .append("/?database=").append(URLEncoder.encode(database, StandardCharsets.UTF_8))
            .append("&query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));

        if (dedupEnabled && deduplicationToken != null && !deduplicationToken.isBlank()) {
            uriBuilder.append("&insert_deduplication_token=")
                .append(URLEncoder.encode(deduplicationToken, StandardCharsets.UTF_8));
        }

        URI uri = URI.create(uriBuilder.toString());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "text/tab-separated-values; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload));

        if (user != null && !user.isBlank()) {
            requestBuilder.header("X-ClickHouse-User", user);
            if (password != null) {
                requestBuilder.header("X-ClickHouse-Key", password);
            }
        }

        Timer.Sample sample = Timer.start();
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            sample.stop(insertLatency);

            if (response.statusCode() != 200) {
                errorsTotal.increment();
                log.error("ClickHouse batch insert failed. Status: {}, Body: {}, Token: {}",
                    response.statusCode(), response.body(), deduplicationToken);
                throw new RuntimeException("ClickHouse insert failed HTTP " + response.statusCode() + ": " + response.body());
            }

            insertsTotal.increment();
            rowsTotal.increment(events.size());
            log.debug("Successfully inserted {} rows into ClickHouse. Token: {}", events.size(), deduplicationToken);

        } catch (IOException | InterruptedException e) {
            sample.stop(insertLatency);
            errorsTotal.increment();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("ClickHouse insert connection error. Token: {}", deduplicationToken, e);
            throw new RuntimeException("ClickHouse connection error", e);
        }
    }

    @Override
    public boolean ping() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(clickhouseUrl + "/ping"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("Ok");
        } catch (Exception e) {
            log.warn("ClickHouse ping failed: {}", e.getMessage());
            return false;
        }
    }
}
