package com.loginsight.anomaly;

import com.loginsight.common.AlertEvent;
import com.loginsight.common.AlertEvent.Severity;
import com.loginsight.common.LogEntry;
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
 *   currentBucket  >=  avg(prior 4 buckets, excluding tainted)  *  6.0   →  alert
 * </pre>
 *
 * <p>Two safety nets keep this from silently swallowing real spikes:
 *
 * <ul>
 *   <li><b>Cold-start floor</b> — when no usable baseline exists yet (first minutes after
 *       startup, or after a long quiet period) the detector fires anyway if the current bucket
 *       alone exceeds {@value #COLD_START_FLOOR} error events. This catches the first spike,
 *       which would otherwise be lost to the baseline check.</li>
 *   <li><b>Tainted-bucket exclusion</b> — once a bucket fires an anomaly it is marked tainted
 *       and excluded from future baseline calculations. Without this, a prior spike's events
 *       sit in the rolling window and inflate the baseline, suppressing the next spike.</li>
 * </ul>
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
    /** Absolute event count that triggers a cold-start alert when no usable baseline exists yet.
     *  Prevents the first spike after startup from being silently suppressed. */
    private static final long   COLD_START_FLOOR  = 50;
    /** Log a suppression line when the current bucket has at least this many events but the
     *  spike condition still isn't met — makes "near-miss" spikes visible in the console. */
    private static final long   INTERESTING_CURRENT_COUNT = 20;

    private final Consumer<AlertEvent> alertSink;
    private final Runnable             resolveSink;
    private final Map<WindowKey, SlidingWindow> windows          = new ConcurrentHashMap<>();
    private final List<AlertEvent>              alerts           = new CopyOnWriteArrayList<>();
    private final Set<WindowKey>                activeAnomalyKeys = ConcurrentHashMap.newKeySet();

    /**
     * @param alertSink   receives every newly-fired {@link AlertEvent}; called synchronously on the ingesting thread.
     *                    Telemetry recording is the caller's responsibility.
     * @param resolveSink called when a (service, statusCode) pair that was previously spiking drops
     *                    back below the threshold — use this to decrement an active-anomaly gauge.
     */
    public AnomalyDetector(Consumer<AlertEvent> alertSink, Runnable resolveSink) {
        this.alertSink   = Objects.requireNonNull(alertSink,   "alertSink");
        this.resolveSink = Objects.requireNonNull(resolveSink, "resolveSink");
    }

    /** Convenience constructor for callers that do not need resolution callbacks. */
    public AnomalyDetector(Consumer<AlertEvent> alertSink) {
        this(alertSink, () -> {});
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
        long now = System.currentTimeMillis();
        SlidingWindow.Snapshot snap = window.snapshot(now);
        long[]    buckets = snap.counts();
        boolean[] tainted = snap.tainted();

        long currentCount    = buckets[WINDOW_SLOTS - 1];
        long baselineTotal   = 0;
        int  filledSlots     = 0;
        int  excludedTainted = 0;

        for (int i = 0; i < WINDOW_SLOTS - 1; i++) {
            if (tainted[i]) { excludedTainted++; continue; }
            if (buckets[i] > 0) {
                baselineTotal += buckets[i];
                filledSlots++;
            }
        }

        boolean hasBaseline = filledSlots > 0 && baselineTotal >= MIN_BASELINE_EVENTS;

        if (!hasBaseline) {
            if (currentCount >= COLD_START_FLOOR) {
                double syntheticBaseline = (double) COLD_START_FLOOR;
                double spikePct = ((currentCount - syntheticBaseline) / syntheticBaseline) * 100.0;
                window.taintCurrent(now);
                fireAlert(key, syntheticBaseline, currentCount, spikePct);
                return;
            }
            if (currentCount >= INTERESTING_CURRENT_COUNT && window.markSuppressionLog(now)) {
                log.info("Suppressed (no baseline): service={} statusCode={} currentCount={} coldStartFloor={} excludedTaintedBuckets={}",
                        key.service(), key.statusCode(), currentCount, COLD_START_FLOOR, excludedTainted);
            }
            return;
        }

        double baselineRate = (double) baselineTotal / filledSlots;
        double currentRate  = currentCount;
        double threshold    = baselineRate * SPIKE_MULTIPLIER;

        if (currentRate >= threshold) {
            double spikePct = ((currentRate - baselineRate) / baselineRate) * 100.0;
            window.taintCurrent(now);
            fireAlert(key, baselineRate, currentRate, spikePct);
        } else if (activeAnomalyKeys.remove(key)) {
            resolveSink.run();
            log.info("RESOLVED: anomaly cleared for service={} statusCode={}", key.service(), key.statusCode());
        } else if (currentCount >= INTERESTING_CURRENT_COUNT && window.markSuppressionLog(now)) {
            log.info("Suppressed (below 6× threshold): service={} statusCode={} current={} baseline={} need>={} excludedTaintedBuckets={}",
                    key.service(), key.statusCode(), currentCount,
                    String.format("%.1f", baselineRate),
                    String.format("%.1f", threshold),
                    excludedTainted);
        }
    }

    private void fireAlert(WindowKey key, double baselineRate, double currentRate, double spikePct) {
        activeAnomalyKeys.add(key);
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

    private record WindowKey(String service, int statusCode) {}

    /**
     * Thread-safe circular-buffer of 1-minute event counts.
     *
     * <p>Each slot is identified by its bucket ID ({@code epochMs / BUCKET_WIDTH_MS}).
     * When time advances past the current slot, stale slots are zeroed before writing,
     * so the window naturally expires old data without a background sweep. Per-slot
     * {@code tainted} flags ride along so a previously-anomalous bucket can be excluded
     * from baseline calculations until it ages out.
     */
    private static final class SlidingWindow {

        private final long[]    counts    = new long[WINDOW_SLOTS];
        private final long[]    bucketIds = new long[WINDOW_SLOTS];
        private final boolean[] tainted   = new boolean[WINDOW_SLOTS];
        private long lastBucketId             = Long.MIN_VALUE;
        private long lastSuppressionLogBucket = Long.MIN_VALUE;

        synchronized void record(long epochMs) {
            long bucketId = epochMs / BUCKET_WIDTH_MS;
            advance(bucketId);
            counts[(int) (bucketId % WINDOW_SLOTS)]++;
        }

        /** Marks the current bucket as anomalous so future baseline calculations exclude it. */
        synchronized void taintCurrent(long nowMs) {
            long currentBucketId = nowMs / BUCKET_WIDTH_MS;
            advance(currentBucketId);
            tainted[slotFor(currentBucketId)] = true;
        }

        /** Returns true at most once per minute per window — throttles suppression logging. */
        synchronized boolean markSuppressionLog(long nowMs) {
            long currentBucketId = nowMs / BUCKET_WIDTH_MS;
            if (lastSuppressionLogBucket == currentBucketId) return false;
            lastSuppressionLogBucket = currentBucketId;
            return true;
        }

        synchronized Snapshot snapshot(long nowMs) {
            long currentBucketId = nowMs / BUCKET_WIDTH_MS;
            advance(currentBucketId);

            long[]    resultCounts  = new long[WINDOW_SLOTS];
            boolean[] resultTainted = new boolean[WINDOW_SLOTS];
            for (int i = 0; i < WINDOW_SLOTS; i++) {
                long targetId = currentBucketId - (WINDOW_SLOTS - 1 - i);
                int  slot     = slotFor(targetId);
                boolean fresh = bucketIds[slot] == targetId;
                resultCounts[i]  = fresh ? counts[slot]  : 0;
                resultTainted[i] = fresh && tainted[slot];
            }
            return new Snapshot(resultCounts, resultTainted);
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
                tainted[slot]   = false;
            }
            lastBucketId = newBucketId;
        }

        private static int slotFor(long bucketId) {
            return (int) ((bucketId % WINDOW_SLOTS + WINDOW_SLOTS) % WINDOW_SLOTS);
        }

        record Snapshot(long[] counts, boolean[] tainted) {}
    }
}
