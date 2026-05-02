package com.loginsight.storage;

import com.loginsight.common.LogEntry;
import com.loginsight.common.MetricSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-service rolling counters that emit a {@link MetricSnapshot} to InfluxDB on a
 * fixed interval. Designed to live in the ingestion process and be fed from the same
 * batch that is bulk-indexed into Elasticsearch.
 *
 * <p>Counters are reset every flush window so the snapshot carries the
 * messages-per-second and error-rate observed during that window only.
 */
public final class MetricsAggregator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricsAggregator.class);

    private final InfluxDbWriter writer;
    private final long flushIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ServiceCounters> counters = new ConcurrentHashMap<>();

    public MetricsAggregator(InfluxDbWriter writer, long flushIntervalSeconds) {
        this.writer = Objects.requireNonNull(writer, "writer");
        if (flushIntervalSeconds <= 0) {
            throw new IllegalArgumentException("flushIntervalSeconds must be > 0");
        }
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("metrics-flush").factory()
        );
    }

    /** Increments the per-service counters; safe to call from any thread. */
    public void record(LogEntry entry) {
        ServiceCounters c = counters.computeIfAbsent(entry.service(), k -> new ServiceCounters());
        c.total.increment();
        if (entry.isError()) c.errors.increment();
    }

    /** Records that an anomaly was detected for the given service. */
    public void recordAnomaly(String service) {
        counters.computeIfAbsent(service, k -> new ServiceCounters()).anomalies.increment();
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::flush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
        log.info("MetricsAggregator started — flush interval {}s", flushIntervalSeconds);
    }

    private void flush() {
        Instant now = Instant.now();
        for (Map.Entry<String, ServiceCounters> e : counters.entrySet()) {
            ServiceCounters c = e.getValue();
            long total     = c.total.sumThenReset();
            long errors    = c.errors.sumThenReset();
            long anomalies = c.anomalies.sumThenReset();
            if (total == 0 && errors == 0 && anomalies == 0) continue;

            double mps       = (double) total / flushIntervalSeconds;
            double errorRate = total > 0 ? Math.min(1.0, (double) errors / total) : 0.0;

            try {
                writer.write(new MetricSnapshot(e.getKey(), mps, errorRate, anomalies, now));
            } catch (Exception ex) {
                log.warn("InfluxDB write failed for service={}: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        flush();
    }

    private static final class ServiceCounters {
        final LongAdder total     = new LongAdder();
        final LongAdder errors    = new LongAdder();
        final LongAdder anomalies = new LongAdder();
    }
}
