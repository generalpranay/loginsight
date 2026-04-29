package com.loginsight.api;

import com.loginsight.anomaly.AnomalyDetector;
import com.loginsight.common.AlertEvent;
import com.loginsight.common.LogEntry;
import com.loginsight.common.MetricSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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

    private final AnomalyDetector anomalyDetector;
    private final LogQueryService logQueryService;

    public LogIngestionController(AnomalyDetector anomalyDetector, LogQueryService logQueryService) {
        this.anomalyDetector = anomalyDetector;
        this.logQueryService = logQueryService;
    }

    /**
     * Returns raw log entries matching the supplied filters, ordered newest-first.
     *
     * @param service optional service-name filter (exact match)
     * @param from    ISO-8601 lower bound, inclusive (defaults to 1 hour ago)
     * @param to      ISO-8601 upper bound, exclusive (defaults to now)
     * @param limit   max results, 1–1 000 (default 100)
     */
    @GetMapping("/logs")
    public ResponseEntity<List<LogEntry>> getLogs(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "100") int limit) {

        int clampedLimit = Math.min(Math.max(1, limit), 1_000);
        Instant fromInstant = from != null ? Instant.parse(from) : Instant.now().minusSeconds(3_600);
        Instant toInstant   = to   != null ? Instant.parse(to)   : Instant.now();

        log.debug("GET /logs service={} from={} to={} limit={}", service, fromInstant, toInstant, clampedLimit);
        return ResponseEntity.ok(logQueryService.queryLogs(service, fromInstant, toInstant, clampedLimit));
    }

    /**
     * Returns anomaly alerts fired since startup, newest-first.
     *
     * @param service optional service-name filter (exact match)
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<AlertEvent>> getAlerts(
            @RequestParam(required = false) String service) {

        List<AlertEvent> alerts = service != null
                ? anomalyDetector.getAlertsForService(service)
                : anomalyDetector.getRecentAlerts().stream()
                        .sorted((a, b) -> b.detectedAt().compareTo(a.detectedAt()))
                        .toList();

        return ResponseEntity.ok(alerts);
    }

    /**
     * Returns the most-recent metric snapshot for the requested service.
     * Responds with {@code 404} when no data is available in InfluxDB.
     *
     * @param service the service to query; required
     */
    @GetMapping("/metrics/summary")
    public ResponseEntity<?> getMetricsSummary(@RequestParam String service) {
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

    /** Kubernetes liveness / readiness probe endpoint. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "timestamp", Instant.now().toString()));
    }
}
