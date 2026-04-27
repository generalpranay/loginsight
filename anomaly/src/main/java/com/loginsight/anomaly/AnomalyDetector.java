package com.loginsight.anomaly;

import com.loginsight.common.AlertEvent;
import com.loginsight.common.AlertEvent.Severity;
import com.loginsight.common.LogEntry;
import com.loginsight.telemetry.TelemetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Sliding-window anomaly detector for HTTP error-code rates.
 *
 * <p>The detector maintains a 5-minute window partitioned into five 1-minute buckets for each
 * unique (service, statusCode) pair seen in the log stream. On every ingested error event it
 * evaluates the spike condition:
 *
 * <pre>
 *   currentBucket  >=  avg(prior 4 buckets)  *  6.0   →  alert
 * </pre>
 *
 * <p>The multiplier of 6.0 means the current minute must contain at least 500 % more events
 * than the baseline average before an alert fires. At least {@value MIN_BASELINE_EVENTS}
 * baseline events must exist across the prior four buckets to suppress false positives at
 * startup or after extended quiet periods.
 *
 * <p>Thread-safe: designed for concurrent ingestion from multiple virtual threads.
 *
 * <p>Non-error log entries (statusCode &lt; 400) are ignored — the detector is intentionally
 * narrow so as not to alert on traffic volume alone.
 */
public final class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    /** 6× baseline = current is 500 % above baseline. */
    private static final double SPIKE_MULTIPLIER  = 6.0;
    private static final int    WINDOW_SLOTS      = 5;
    private static final long   BUCKET_WIDTH_MS   = 60_000L;
    /** Minimum total events in the baseline slots before the rule can fire. */
    private static final int    MIN_BASELINE_EVENTS = 5;

    private final Consumer<AlertEvent> alertSink;
    private final TelemetryConfig telemetry;
    private final Map<WindowKey, SlidingWindow> windows  = new ConcurrentHashMap<>();
    private final List<AlertEvent>              alerts   = new CopyOnWriteArrayList<>();

    /**
     * @param alertSink receives every newly-fired {@link AlertEvent}; called synchronously on the ingesting thread
     * @param telemetry OTel instrumentation
     */
    public AnomalyDetector(Consumer<AlertEvent> alertSink, TelemetryConfig telemetry) {
        this.alertSink = Objects.requireNonNull(alertSink, "alertSink");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /**
     * Ingests one log entry. Only error entries (HTTP 4xx/5xx) update the window;
     * all others are ignored for efficiency.
     */
    public void ingest(LogEntry entry) {
        if (!entry.isError()) return;
        WindowKey key = new WindowKey(entry.service(), entry.statusCode());
        SlidingWindow window = windows.computeIfAbsent(key, k -> new SlidingWindow());
        window.record(entry.timestamp().toEpochMilli());
        evaluate(key, window);
    }

    /** Returns an unmodifiable view of all alerts fired since startup. */
    public List<AlertEvent> getRecentAlerts() {
        return Collections.unmodifiableList(alerts);
    }

    /** Returns all alerts for a specific service, most-recent-first. */
    public List<AlertEvent> getAlertsForService(String service) {
        return alerts.stream()
                .filter(a -> a.service().equals(service))
                .sorted(Comparator.comparing(AlertEvent::detectedAt).reversed())
                .toList();
    }

    private void evaluate(WindowKey key, SlidingWindow window) {
        long[] buckets = window.snapshot(System.currentTimeMillis());

        long currentCount  = buckets[WINDOW_SLOTS - 1];
        long baselineTotal = 0;
        int  filledSlots   = 0;

        for (int i = 0; i < WINDOW_SLOTS - 1; i++) {
            if (buckets[i] > 0) {
                baselineTotal += buckets[i];
                filledSlots++;
            }
        }

        if (filledSlots == 0 || baselineTotal < MIN_BASELINE_EVENTS) return;

        double baselineRate = (double) baselineTotal / filledSlots;
        double currentRate  = currentCount;

        if (currentRate >= baselineRate * SPIKE_MULTIPLIER) {
            double spikePct  = ((currentRate - baselineRate) / baselineRate) * 100.0;
            Severity severity = spikePct >= 1_000.0 ? Severity.CRITICAL : Severity.WARNING;

            AlertEvent alert = new AlertEvent(
                    UUID.randomUUID().toString(),
                    key.service(),
                    key.statusCode(),
                    baselineRate,
                    currentRate,
                    spikePct,
                    Instant.now(),
                    severity
            );

            alerts.add(alert);
            alertSink.accept(alert);
            log.warn("ANOMALY: {}", alert.toSummary());
        }
    }

    private record WindowKey(String service, int statusCode) {}

    /**
     * Thread-safe circular-buffer of 1-minute event counts.
     *
     * <p>Each slot is identified by its bucket ID ({@code epochMs / BUCKET_WIDTH_MS}).
     * When time advances past the current slot, stale slots are zeroed before writing,
     * so the window naturally expires old data without a background sweep.
     */
    private static final class SlidingWindow {

        private final long[] counts    = new long[WINDOW_SLOTS];
        private final long[] bucketIds = new long[WINDOW_SLOTS];
        private long lastBucketId = Long.MIN_VALUE;

        synchronized void record(long epochMs) {
            long bucketId = epochMs / BUCKET_WIDTH_MS;
            advance(bucketId);
            counts[(int) (bucketId % WINDOW_SLOTS)]++;
        }

        synchronized long[] snapshot(long nowMs) {
            long currentBucketId = nowMs / BUCKET_WIDTH_MS;
            advance(currentBucketId);

            long[] result = new long[WINDOW_SLOTS];
            for (int i = 0; i < WINDOW_SLOTS; i++) {
                long targetId = currentBucketId - (WINDOW_SLOTS - 1 - i);
                int  slot     = slotFor(targetId);
                result[i]     = (bucketIds[slot] == targetId) ? counts[slot] : 0;
            }
            return result;
        }

        private void advance(long newBucketId) {
            if (lastBucketId == Long.MIN_VALUE) {
                lastBucketId = newBucketId;
                int slot = slotFor(newBucketId);
                bucketIds[slot] = newBucketId;
                return;
            }
            long gap = newBucketId - lastBucketId;
            if (gap <= 0) return;

            long slotsToReset = Math.min(gap, WINDOW_SLOTS);
            for (long i = 1; i <= slotsToReset; i++) {
                long id   = lastBucketId + i;
                int  slot = slotFor(id);
                counts[slot]    = 0;
                bucketIds[slot] = id;
            }
            lastBucketId = newBucketId;
        }

        private static int slotFor(long bucketId) {
            return (int) ((bucketId % WINDOW_SLOTS + WINDOW_SLOTS) % WINDOW_SLOTS);
        }
    }
}
