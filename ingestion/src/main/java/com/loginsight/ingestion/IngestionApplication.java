package com.loginsight.ingestion;

import com.loginsight.anomaly.AnomalyDetector;
import com.loginsight.common.LogEntry;
import com.loginsight.storage.ElasticsearchWriter;
import com.loginsight.storage.InfluxDbWriter;
import com.loginsight.storage.S3ArchivalWorker;
import com.loginsight.telemetry.TelemetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Standalone entry point for the ingestion worker process.
 *
 * <p>Wires together the full processing pipeline on startup:
 * <pre>
 *   KafkaLogConsumer  →  AnomalyDetector  →  ElasticsearchWriter (bulk)
 *                    ↘                    ↘  InfluxDbWriter (metrics)
 *                       alert channel    →  (logged + available via REST)
 * </pre>
 *
 * <p>The S3ArchivalWorker runs independently on a 24-hour schedule inside the same JVM.
 * In Kubernetes, this class is the entry point for the ingestion Deployment (3 replicas);
 * the REST API layer runs as a separate Deployment from the {@code api} module.
 */
public final class IngestionApplication {

    private static final Logger log = LoggerFactory.getLogger(IngestionApplication.class);

    public static void main(String[] args) throws Exception {
        TelemetryConfig telemetry        = TelemetryConfig.initialize();
        ElasticsearchWriter esWriter     = new ElasticsearchWriter();
        InfluxDbWriter influxWriter      = new InfluxDbWriter();
        S3ArchivalWorker archivalWorker  = new S3ArchivalWorker(esWriter);
        AnomalyDetector detector         = new AnomalyDetector(
                alert -> {
                    log.warn("ALERT: {}", alert.toSummary());
                    telemetry.recordAnomalyDetected(alert.service(), alert.statusCode());
                },
                telemetry
        );

        KafkaLogConsumer consumer = new KafkaLogConsumer(
                (List<LogEntry> batch) -> {
                    for (LogEntry entry : batch) detector.ingest(entry);
                    esWriter.bulkWrite(batch);
                },
                telemetry
        );

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutdown hook triggered");
            consumer.close();
            archivalWorker.close();
            influxWriter.close();
            try { esWriter.close(); } catch (Exception e) { log.warn("ES close error", e); }
            telemetry.close();
        }));

        archivalWorker.start();
        consumer.startAsync();
        consumer.awaitShutdown();
    }
}
