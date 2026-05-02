package com.loginsight.ingestion;

import com.loginsight.anomaly.AnomalyDetector;
import com.loginsight.common.LogEntry;
import com.loginsight.storage.ElasticsearchWriter;
import com.loginsight.storage.InfluxDbWriter;
import com.loginsight.storage.MetricsAggregator;
import com.loginsight.storage.S3ArchivalWorker;
import com.loginsight.telemetry.TelemetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Standalone entry point for the <strong>ingestion worker</strong> process.
 *
 * <h2>Component wiring</h2>
 * <pre>
 *   Kafka (raw-logs)
 *       │
 *       ▼
 *   KafkaLogConsumer  ──────────────────────────────► ElasticsearchWriter
 *   (EOS poll loop,                                   (bulk index, day-partitioned)
 *    manual offset commit)
 *       │
 *       ├──► AnomalyDetector  ──► AlertPublisher ──► Kafka (anomaly-alerts)
 *       │    (sliding-window       (idempotent
 *       │     spike detection)      producer)
 *       │
 *       └──► MetricsAggregator ──► InfluxDbWriter
 *            (per-service           (flush every
 *             counters)              N seconds)
 *
 *   S3ArchivalWorker  (24-hour schedule, independent of the consumer loop)
 *       └──► ElasticsearchWriter.findAllInIndex()
 *       └──► S3Client.putObject()
 *       └──► ElasticsearchWriter.deleteIndex()   ← only after S3 confirms write
 * </pre>
 *
 * <h2>At-least-once delivery guarantee</h2>
 * <p>Kafka offsets are committed only after {@link ElasticsearchWriter#bulkWrite} returns
 * successfully. If the ES write fails, the offset is not advanced and Kafka will redeliver
 * the batch on the next poll cycle. ES document IDs are deterministic (set on the producer
 * side), so redelivery results in idempotent overwrites rather than duplicates.
 *
 * <h2>Startup order</h2>
 * <ol>
 *   <li>Telemetry (Prometheus exporter) must be first so all downstream counters are registered.</li>
 *   <li>Storage writers (ES, InfluxDB) are created next; they fail fast if their URLs are missing.</li>
 *   <li>MetricsAggregator and S3ArchivalWorker are started before the consumer so no events
 *       are dropped between consumer start and the first flush tick.</li>
 *   <li>KafkaLogConsumer is started last and blocks the main thread via {@code awaitShutdown()}.</li>
 * </ol>
 *
 * <h2>Graceful shutdown</h2>
 * <p>A JVM shutdown hook (triggered by SIGTERM in Kubernetes) runs all {@code close()} calls
 * in dependency order — consumer first (stops polling), then workers, then storage, then
 * telemetry. This ensures in-flight batches are flushed before the process exits.
 */
public final class IngestionApplication {

    private static final Logger log = LoggerFactory.getLogger(IngestionApplication.class);

    public static void main(String[] args) throws Exception {
        long flushSeconds = parseLong("METRICS_FLUSH_INTERVAL_SECONDS", 10L);

        TelemetryConfig    telemetry      = TelemetryConfig.initialize();
        ElasticsearchWriter esWriter      = new ElasticsearchWriter(System.getenv("ELASTICSEARCH_URL"));
        InfluxDbWriter     influxWriter   = new InfluxDbWriter(
                System.getenv("INFLUXDB_URL"),
                System.getenv("INFLUXDB_TOKEN"),
                System.getenv("INFLUXDB_ORG"),
                System.getenv("INFLUXDB_BUCKET"));
        MetricsAggregator  aggregator     = new MetricsAggregator(influxWriter, flushSeconds);
        S3ArchivalWorker   archivalWorker = new S3ArchivalWorker(esWriter);
        AlertPublisher     alertPublisher = new AlertPublisher();

        AnomalyDetector detector = new AnomalyDetector(alert -> {
            log.warn("ALERT: {}", alert.toSummary());
            telemetry.recordAnomalyDetected(alert.service(), alert.statusCode());
            aggregator.recordAnomaly(alert.service());
            alertPublisher.publish(alert);
        });

        KafkaLogConsumer consumer = new KafkaLogConsumer(
                (List<LogEntry> batch) -> {
                    for (LogEntry entry : batch) {
                        detector.ingest(entry);
                        aggregator.record(entry);
                    }
                    try {
                        esWriter.bulkWrite(batch);
                    } catch (IOException e) {
                        // Surface as runtime so the consumer skips the commit — Kafka will redeliver.
                        throw new RuntimeException("ES bulkWrite failed; offsets will not commit", e);
                    }
                },
                telemetry
        );

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutdown hook triggered");
            safeClose("consumer",       consumer);
            safeClose("archivalWorker", archivalWorker);
            safeClose("aggregator",     aggregator);
            safeClose("alertPublisher", alertPublisher);
            safeClose("influxWriter",   influxWriter);
            safeClose("esWriter",       esWriter);
            safeClose("telemetry",      telemetry);
        }));

        archivalWorker.start();
        aggregator.start();
        consumer.startAsync();
        consumer.awaitShutdown();
    }

    private static long parseLong(String name, long defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static void safeClose(String name, AutoCloseable c) {
        try { c.close(); } catch (Exception e) { log.warn("Error closing {}: {}", name, e.getMessage()); }
    }
}
