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
 * Accumulates per-service throughput, error, and anomaly counters in memory and
 * flushes a {@link MetricSnapshot} to InfluxDB on a fixed schedule.
 *
 * <h2>How it fits in the pipeline</h2>
 * <p>The ingestion process feeds every consumed {@link com.loginsight.common.LogEntry}
 * into both {@link ElasticsearchWriter} (for durable storage) and this aggregator
 * (for live metrics). The aggregator runs its own virtual-thread flush job and writes
 * to InfluxDB independently — a flush failure never blocks or retries the Kafka consumer.
 *
 * <h2>Counter design</h2>
 * <p>Counters use {@link java.util.concurrent.atomic.LongAdder} rather than
 * {@code AtomicLong}. Under high concurrency, {@code LongAdder} avoids CAS contention
 * by maintaining a cell per thread and summing on read, making it the right choice for
 * high-frequency increment workloads like this one.
 *
 * <h2>Windowed (not cumulative) metrics</h2>
 * <p>Counters are reset ({@code sumThenReset}) on every flush so the snapshot reflects
 * the rate <em>during that interval only</em>, not a cumulative total. This produces
 * time-series data that Grafana can graph as a rate gauge without needing to compute
 * a derivative.
 *
 * <p>A new service key is registered lazily on first {@link #record} call; no
 * pre-configuration is needed.
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

    /**
     * Records one log entry against its service counters.
     * Safe to call concurrently from multiple threads (e.g. the Kafka poll thread
     * and any future parallel-processing threads).
     *
     * @param entry the log entry just consumed from Kafka
     */
    public void record(LogEntry entry) {
        ServiceCounters c = counters.computeIfAbsent(entry.service(), k -> new ServiceCounters());
        c.total.increment();
        if (entry.isError()) c.errors.increment();
    }

    /**
     * Increments the anomaly counter for the given service.
     * Called by the {@link com.loginsight.anomaly.AnomalyDetector} alert sink
     * whenever a spike threshold is exceeded.
     *
     * @param service the service for which an anomaly was detected
     */
    public void recordAnomaly(String service) {
        counters.computeIfAbsent(service, k -> new ServiceCounters()).anomalies.increment();
    }

    /**
     * Starts the background flush job on a single virtual thread.
     * Must be called once after construction; calling multiple times is undefined.
     */
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

    /**
     * Shuts down the scheduler and performs one final flush to capture any
     * counters that accumulated since the last scheduled run.
     * Called by the JVM shutdown hook in {@code IngestionApplication}.
     */
    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        // Final flush ensures the last partial window is not silently dropped on shutdown
        flush();
    }

    private static final class ServiceCounters {
        final LongAdder total     = new LongAdder();
        final LongAdder errors    = new LongAdder();
        final LongAdder anomalies = new LongAdder();
    }
}
