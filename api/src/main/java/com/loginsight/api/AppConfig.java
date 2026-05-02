package com.loginsight.api;

import com.loginsight.storage.ElasticsearchWriter;
import com.loginsight.storage.InfluxDbWriter;
import com.loginsight.telemetry.TelemetryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@code @Configuration} that wires the non-Spring infrastructure components
 * into the application context as managed beans.
 *
 * <h2>Why plain-Java storage classes?</h2>
 * <p>{@link com.loginsight.storage.ElasticsearchWriter} and
 * {@link com.loginsight.storage.InfluxDbWriter} deliberately contain no Spring annotations.
 * This means the same JARs can be used in the standalone ingestion process
 * ({@code IngestionApplication.main}) without pulling in the Spring container —
 * a significant startup-time and footprint saving for a long-running worker process.
 * This config class acts as the adapter between Spring's DI world and those plain objects.
 *
 * <h2>Graceful degradation pattern</h2>
 * <p>Connection URLs are injected from Spring properties with empty-string defaults
 * (e.g. {@code loginsight.elasticsearch.url=${ELASTICSEARCH_URL:}}). Each writer checks
 * at construction time whether its URL is blank; if so it logs a warning and operates
 * as a no-op. This lets the API start cleanly in local development or CI environments
 * where Elasticsearch and InfluxDB are not running, mirroring the same pattern used by
 * {@link AlertSubscriber} for Kafka.
 *
 * <h2>What the API process does NOT run</h2>
 * <ul>
 *   <li><strong>AnomalyDetector</strong> — runs only inside the ingestion process; the API
 *       receives alert state via the {@code anomaly-alerts} Kafka topic through
 *       {@link AlertSubscriber}.</li>
 *   <li><strong>S3ArchivalWorker</strong> — runs only inside the ingestion process; running
 *       it in two places would cause duplicate ES index deletes during a rebalance.</li>
 *   <li><strong>MetricsAggregator</strong> — also ingestion-side only; the API reads
 *       pre-computed snapshots from InfluxDB rather than re-computing them.</li>
 * </ul>
 */
@Configuration
public class AppConfig {

    @Value("${loginsight.elasticsearch.url:}")
    private String elasticsearchUrl;

    @Value("${loginsight.influxdb.url:}")
    private String influxDbUrl;

    @Value("${loginsight.influxdb.token:}")
    private String influxDbToken;

    @Value("${loginsight.influxdb.org:}")
    private String influxDbOrg;

    @Value("${loginsight.influxdb.bucket:}")
    private String influxDbBucket;

    @Bean(destroyMethod = "close")
    public TelemetryConfig telemetryConfig() {
        return TelemetryConfig.initialize();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchWriter elasticsearchWriter() {
        return new ElasticsearchWriter(elasticsearchUrl);
    }

    @Bean(destroyMethod = "close")
    public InfluxDbWriter influxDbWriter() {
        return new InfluxDbWriter(influxDbUrl, influxDbToken, influxDbOrg, influxDbBucket);
    }
}
