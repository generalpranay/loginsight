package com.loginsight.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable record representing a single structured log event as it flows through
 * the ingestion pipeline — from Kafka through Elasticsearch and the anomaly detector.
 *
 * <p>The {@code statusCode} field drives anomaly detection; {@code traceId} enables
 * correlation with distributed traces. {@code tags} carries arbitrary service-defined
 * metadata without schema changes.
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
