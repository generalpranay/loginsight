package com.loginsight.api;

import com.loginsight.common.LogEntry;
import com.loginsight.common.MetricSnapshot;
import com.loginsight.storage.ElasticsearchWriter;
import com.loginsight.storage.InfluxDbWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Application-layer service that delegates read queries to the appropriate
 * storage backends and shields the REST layer from storage exceptions.
 *
 * <h2>Responsibility boundary</h2>
 * <p>This class sits between {@link LogIngestionController} (HTTP) and the storage
 * module ({@link com.loginsight.storage.ElasticsearchWriter},
 * {@link com.loginsight.storage.InfluxDbWriter}). It owns one specific concern:
 * ensuring that a connectivity failure or timeout in a storage backend returns a
 * graceful empty result to the caller rather than a 500 error or an unhandled exception.
 *
 * <h2>Error-handling strategy</h2>
 * <p>All storage calls are wrapped in try/catch. On failure, the method logs the error
 * with full context (service name, time range) and returns an empty/null result.
 * This is intentional: a read failure on the metrics or log endpoint should not
 * cascade into an API outage — callers can display a "no data available" state instead.
 * Write-path failures (ingestion) carry stricter semantics and are handled by
 * the ingestion process, not here.
 */
@Service
public class LogQueryService {

    private static final Logger log = LoggerFactory.getLogger(LogQueryService.class);

    private final ElasticsearchWriter esWriter;
    private final InfluxDbWriter influxWriter;

    public LogQueryService(ElasticsearchWriter esWriter, InfluxDbWriter influxWriter) {
        this.esWriter     = esWriter;
        this.influxWriter = influxWriter;
    }

    /**
     * Returns log entries matching the given criteria from Elasticsearch, ordered newest-first.
     *
     * <p>Searches the {@code logs-*} index alias, which covers all day-partitioned indices
     * created by the ingestion process (e.g. {@code logs-2024.01.15}). On any storage
     * error the method logs the failure and returns an empty list so the controller
     * can return HTTP 200 with an empty array rather than HTTP 500.
     *
     * @param service nullable service filter; when non-null adds a term query on the {@code service} field
     * @param from    lower bound (inclusive) of the timestamp range
     * @param to      upper bound (exclusive) of the timestamp range
     * @param limit   maximum number of results to return; already capped at 1 000 by the controller
     * @return matching log entries, newest-first; empty list on error or no results
     */
    public List<LogEntry> queryLogs(String service, Instant from, Instant to, int limit) {
        try {
            return esWriter.search(service, from, to, limit);
        } catch (Exception e) {
            log.error("ES query failed service={} from={} to={}: {}", service, from, to, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Returns the most recent {@link MetricSnapshot} for the given service from InfluxDB.
     *
     * <p>The snapshot is computed by {@code MetricsAggregator} in the ingestion process and
     * written to the {@code log_metrics} InfluxDB measurement on a fixed flush interval.
     * This method queries the last hour's worth of data and returns the most recent point.
     *
     * @param service the service name to look up; must match the tag value written by the aggregator
     * @return the latest snapshot, or {@code null} if no data exists or InfluxDB is unavailable
     */
    public MetricSnapshot getLatestMetricSnapshot(String service) {
        try {
            return influxWriter.queryLatest(service);
        } catch (Exception e) {
            log.error("InfluxDB query failed service={}: {}", service, e.getMessage(), e);
            return null;
        }
    }
}
