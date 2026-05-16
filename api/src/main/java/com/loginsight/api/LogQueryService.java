package com.loginsight.api;

import com.loginsight.common.LogEntry;
import com.loginsight.common.MetricSnapshot;
import com.loginsight.storage.ElasticsearchWriter;
import com.loginsight.storage.InfluxDbWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Application-layer service that delegates read queries to storage backends and the
 * in-memory {@link LogBuffer}, merging results so that simulator and manually submitted
 * logs are visible even when Elasticsearch is not configured.
 */
@Service
public class LogQueryService {

    private static final Logger log = LoggerFactory.getLogger(LogQueryService.class);

    private final ElasticsearchWriter esWriter;
    private final InfluxDbWriter influxWriter;
    private final LogBuffer logBuffer;

    public LogQueryService(ElasticsearchWriter esWriter, InfluxDbWriter influxWriter, LogBuffer logBuffer) {
        this.esWriter   = esWriter;
        this.influxWriter = influxWriter;
        this.logBuffer  = logBuffer;
    }

    /**
     * Returns log entries matching the given criteria, newest-first.
     *
     * <p>Results are sourced from two places and merged:
     * <ol>
     *   <li>Elasticsearch (when configured) — durable, survives restarts</li>
     *   <li>{@link LogBuffer} — in-memory; captures simulator output and manual entries in real time</li>
     * </ol>
     * Entries that appear in both sources are deduplicated by {@code id}. On any ES error
     * the buffer results alone are returned so the UI always shows something.
     */
    public List<LogEntry> queryLogs(String service, Instant from, Instant to, int limit) {
        List<LogEntry> fromEs;
        try {
            fromEs = esWriter.search(service, from, to, limit);
        } catch (Exception e) {
            log.error("ES query failed service={} from={} to={}: {}", service, from, to, e.getMessage());
            fromEs = List.of();
        }

        List<LogEntry> fromBuffer = logBuffer.query(service, from, to, limit);

        if (fromBuffer.isEmpty()) return fromEs;
        if (fromEs.isEmpty())     return fromBuffer;

        // Merge: deduplicate by id, sort newest-first, apply limit
        return Stream.concat(fromEs.stream(), fromBuffer.stream())
                .collect(java.util.stream.Collectors.toMap(
                        LogEntry::id,
                        e -> e,
                        (a, b) -> a,          // keep whichever we saw first (ES wins)
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(LogEntry::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    public MetricSnapshot getLatestMetricSnapshot(String service) {
        try {
            return influxWriter.queryLatest(service);
        } catch (Exception e) {
            log.error("InfluxDB query failed service={}: {}", service, e.getMessage());
            return null;
        }
    }
}
