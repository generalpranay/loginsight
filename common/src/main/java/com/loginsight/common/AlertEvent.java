package com.loginsight.common;

import java.time.Instant;

/**
 * Immutable record representing an anomaly alert fired by the sliding-window detector.
 *
 * <p>{@code baselineRate} is the average events-per-minute over the prior four
 * 1-minute buckets; {@code currentRate} is the most recent bucket. When
 * {@code currentRate / baselineRate >= 6.0} (500% over baseline) the detector
 * emits this record.
 */
public record AlertEvent(
        String alertId,
        String service,
        int statusCode,
        double baselineRate,
        double currentRate,
        double spikePercentage,
        Instant detectedAt,
        Severity severity
) {
    /**
     * Alert urgency tier. {@code CRITICAL} fires when the spike exceeds 1 000 %
     * above baseline; {@code WARNING} covers 500–999 %.
     */
    public enum Severity { WARNING, CRITICAL }

    public AlertEvent {
        if (alertId == null || alertId.isBlank()) throw new IllegalArgumentException("alertId must not be blank");
        if (service == null || service.isBlank()) throw new IllegalArgumentException("service must not be blank");
        if (detectedAt == null)                   throw new IllegalArgumentException("detectedAt must not be null");
    }

    /** One-line human-readable summary suitable for log output and Slack notifications. */
    public String toSummary() {
        return String.format(
                "[%s] %s — HTTP %d spiked +%.0f%% (baseline=%.1f/min, current=%.1f/min) at %s",
                severity, service, statusCode, spikePercentage, baselineRate, currentRate, detectedAt
        );
    }
}
