package com.loginsight.common;

import java.time.Instant;

/**
 * Immutable point-in-time metric snapshot for a single service, covering one flush
 * window of the {@link com.loginsight.storage.MetricsAggregator}.
 *
 * <h2>Data flow</h2>
 * <ol>
 *   <li>The ingestion process computes this snapshot from windowed counters and
 *       writes it to InfluxDB via {@code InfluxDbWriter.write()}.</li>
 *   <li>The API process reads the latest snapshot from InfluxDB via
 *       {@code InfluxDbWriter.queryLatest()} and serves it at
 *       {@code GET /api/v1/metrics/summary?service=...}.</li>
 * </ol>
 *
 * <h2>Field semantics</h2>
 * <ul>
 *   <li>{@code messagesPerSecond} — average ingestion rate during the flush window.</li>
 *   <li>{@code errorRate} — fraction of messages with HTTP 4xx/5xx in the window; in [0, 1].</li>
 *   <li>{@code anomalyCount} — number of anomaly alerts fired during the window.</li>
 * </ul>
 */
public record MetricSnapshot(
        String service,
        double messagesPerSecond,
        double errorRate,
        long anomalyCount,
        Instant timestamp
) {
    public MetricSnapshot {
        if (service == null || service.isBlank()) throw new IllegalArgumentException("service must not be blank");
        if (timestamp == null)                    throw new IllegalArgumentException("timestamp must not be null");
        if (messagesPerSecond < 0)                throw new IllegalArgumentException("messagesPerSecond must be >= 0");
        if (errorRate < 0 || errorRate > 1)       throw new IllegalArgumentException("errorRate must be in [0,1]");
        if (anomalyCount < 0)                     throw new IllegalArgumentException("anomalyCount must be >= 0");
    }
}
