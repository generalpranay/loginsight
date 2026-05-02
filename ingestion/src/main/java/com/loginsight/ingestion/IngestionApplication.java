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
 * Standalone entry point for the ingestion worker process.
 *
 * <p>Wiring on startup:
 * <pre>
 *   KafkaLogConsumer  →  AnomalyDetector  →  AlertPublisher (Kafka topic anomaly-alerts)
 *                    ↘  MetricsAggregator →  InfluxDbWriter (every flushIntervalSeconds)
 *                    ↘  ElasticsearchWriter (bulk index)
 * </pre>
 *
 * <p>The S3ArchivalWorker runs on a 24-hour schedule inside the same JVM. Per-batch
 * indexing is synchronous: Kafka offsets are committed only after ES bulk-write
 * succeeds (at-least-once); duplicates are suppressed downstream by ES doc IDs.
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
