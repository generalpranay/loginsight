package com.loginsight.api;

import com.loginsight.anomaly.AnomalyDetector;
import com.loginsight.storage.ElasticsearchWriter;
import com.loginsight.storage.InfluxDbWriter;
import com.loginsight.storage.S3ArchivalWorker;
import com.loginsight.telemetry.TelemetryConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring bean wiring for non-Spring infrastructure components.
 *
 * <p>All storage and telemetry classes are plain Java — no Spring annotations in the
 * modules that own them. This keeps those modules deployable as standalone JARs
 * (e.g., the ingestion worker) without dragging in the Spring container.
 */
@Configuration
public class AppConfig {

    @Bean(destroyMethod = "close")
    public TelemetryConfig telemetryConfig() {
        return TelemetryConfig.initialize();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchWriter elasticsearchWriter() {
        return new ElasticsearchWriter();
    }

    @Bean(destroyMethod = "close")
    public InfluxDbWriter influxDbWriter() {
        return new InfluxDbWriter();
    }

    @Bean(destroyMethod = "close")
    public S3ArchivalWorker s3ArchivalWorker(ElasticsearchWriter esWriter) {
        S3ArchivalWorker worker = new S3ArchivalWorker(esWriter);
        worker.start();
        return worker;
    }

    @Bean
    public AnomalyDetector anomalyDetector(TelemetryConfig telemetry) {
        return new AnomalyDetector(
                alert -> {
                    telemetry.recordAnomalyDetected(alert.service(), alert.statusCode());
                    // Alert fanout (Slack, PagerDuty, etc.) wired here in production
                },
                telemetry
        );
    }
}
