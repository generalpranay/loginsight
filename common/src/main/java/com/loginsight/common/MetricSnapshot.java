package com.loginsight.common;

import java.time.Instant;

/**
 * Immutable point-in-time metric snapshot for a single service.
 * Written to InfluxDB as one measurement point and read back by the API layer
 * to serve {@code GET /metrics/summary}.
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
