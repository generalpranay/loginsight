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
 * Application-layer service that delegates log and metrics queries to the
 * appropriate storage backends.
 *
 * <p>Sits between the REST controller and the storage module so that
 * transport errors are translated into meaningful HTTP responses at the controller
 * level without leaking storage-layer exceptions to callers.
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
     * Returns log entries matching the given criteria, ordered newest-first.
     *
     * @param service nullable; when non-null, restricts results to that service
     * @param from    lower bound (inclusive); defaults to 1 hour ago when null
     * @param to      upper bound (exclusive); defaults to now when null
     * @param limit   max results; capped at 1 000 by the caller
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
     * Returns the most recent metric snapshot for the given service from InfluxDB,
     * or {@code null} when no data is available.
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
