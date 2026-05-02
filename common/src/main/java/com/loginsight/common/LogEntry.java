package com.loginsight.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable record representing a single structured log event as it flows through
 * the entire Log-to-Insight pipeline: Kafka → ingestion worker → Elasticsearch → REST API.
 *
 * <h2>Field responsibilities</h2>
 * <ul>
 *   <li>{@code id} — unique event identifier; used as the Elasticsearch document ID to
 *       make bulk indexing idempotent (re-indexing the same event is a no-op).</li>
 *   <li>{@code service} — the originating microservice; used as the partition key in Kafka,
 *       the primary filter in ES queries, and the tag in InfluxDB metrics.</li>
 *   <li>{@code statusCode} — HTTP response code; drives the anomaly detector (4xx/5xx only)
 *       and the error-rate calculation in {@code MetricsAggregator}.</li>
 *   <li>{@code traceId} — distributed trace correlation ID; stored in ES for cross-service
 *       incident investigation.</li>
 *   <li>{@code tags} — open-ended key-value metadata (e.g. region, env, version); allows
 *       services to attach context without requiring a schema change.</li>
 * </ul>
 *
 * <p>Deserialization is permissive ({@code @JsonIgnoreProperties(ignoreUnknown = true)}) so
 * new producer fields do not break existing consumer versions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogEntry(
        String id,
        String service,
        String level,
        int statusCode,
        String message,
        String host,
        String traceId,
        Instant timestamp,
        Map<String, String> tags
) {
    public LogEntry {
        if (id == null || id.isBlank())          throw new IllegalArgumentException("id must not be blank");
        if (service == null || service.isBlank()) throw new IllegalArgumentException("service must not be blank");
        if (timestamp == null)                    throw new IllegalArgumentException("timestamp must not be null");
        tags = (tags == null) ? Map.of() : Map.copyOf(tags);
    }

    /** Returns true when {@code statusCode} indicates an HTTP client or server error. */
    public boolean isError() {
        return statusCode >= 400;
    }

    /** Returns true when {@code statusCode} indicates a server-side error (5xx). */
    public boolean isServerError() {
        return statusCode >= 500;
    }
}
