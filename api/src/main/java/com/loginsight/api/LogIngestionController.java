package com.loginsight.api;

import com.loginsight.common.AlertEvent;
import com.loginsight.common.LogEntry;
import com.loginsight.common.MetricSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * REST API exposing log data, anomaly alerts, and metric summaries.
 *
 * <p>All handler methods run on virtual threads (enabled via
 * {@code spring.threads.virtual.enabled=true}), so blocking Elasticsearch and
 * InfluxDB I/O does not exhaust a fixed-size thread pool.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/v1/logs} — raw log entries, filterable by service and time range</li>
 *   <li>{@code GET /api/v1/alerts} — anomaly alerts, filterable by service</li>
 *   <li>{@code GET /api/v1/metrics/summary} — latest metric snapshot for a service</li>
 *   <li>{@code GET /api/v1/health} — liveness probe</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class LogIngestionController {

    private static final Logger log = LoggerFactory.getLogger(LogIngestionController.class);

    private static final int MAX_LIMIT = 1_000;
    private static final int DEFAULT_LIMIT = 100;
    /** Service names: alphanumerics, dash, underscore, dot, max 64 chars. Prevents injection into ES queries / log lines. */
    private static final java.util.regex.Pattern SERVICE_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    private final LogQueryService logQueryService;
    private final AlertSubscriber alertSubscriber;

    public LogIngestionController(LogQueryService logQueryService, AlertSubscriber alertSubscriber) {
        this.logQueryService = logQueryService;
        this.alertSubscriber = alertSubscriber;
    }

    @GetMapping("/logs")
    public ResponseEntity<?> getLogs(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "100") int limit) {

        if (service != null && !SERVICE_PATTERN.matcher(service).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid service name"));
        }

        int clampedLimit = Math.min(Math.max(1, limit), MAX_LIMIT);
        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = from != null ? Instant.parse(from) : Instant.now().minusSeconds(3_600);
            toInstant   = to   != null ? Instant.parse(to)   : Instant.now();
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid ISO-8601 timestamp"));
        }
        if (!fromInstant.isBefore(toInstant)) {
            return ResponseEntity.badRequest().body(Map.of("error", "'from' must be before 'to'"));
        }

        log.debug("GET /logs service={} from={} to={} limit={}", service, fromInstant, toInstant, clampedLimit);
        List<LogEntry> entries = logQueryService.queryLogs(service, fromInstant, toInstant, clampedLimit);
        return ResponseEntity.ok(entries);
    }

    @GetMapping("/alerts")
    public ResponseEntity<?> getAlerts(@RequestParam(required = false) String service) {
        if (service != null && !SERVICE_PATTERN.matcher(service).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid service name"));
        }
        List<AlertEvent> alerts = service != null
                ? alertSubscriber.getForService(service)
                : alertSubscriber.getRecent();
        return ResponseEntity.ok(alerts);
    }

    @GetMapping("/metrics/summary")
    public ResponseEntity<?> getMetricsSummary(@RequestParam String service) {
        if (!SERVICE_PATTERN.matcher(service).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid service name"));
        }
        MetricSnapshot snapshot = logQueryService.getLatestMetricSnapshot(service);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "service",            snapshot.service(),
                "messagesPerSecond",  snapshot.messagesPerSecond(),
                "errorRate",          snapshot.errorRate(),
                "anomalyCount",       snapshot.anomalyCount(),
                "timestamp",          snapshot.timestamp().toString()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "timestamp", Instant.now().toString()));
    }
}
