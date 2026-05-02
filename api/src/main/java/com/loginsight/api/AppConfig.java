package com.loginsight.api;

import com.loginsight.storage.ElasticsearchWriter;
import com.loginsight.storage.InfluxDbWriter;
import com.loginsight.telemetry.TelemetryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring bean wiring for non-Spring infrastructure components.
 *
 * <p>All storage and telemetry classes are plain Java — no Spring annotations in the
 * modules that own them. This keeps those modules deployable as standalone JARs
 * (e.g., the ingestion worker) without dragging in the Spring container.
 *
 * <p>The API process does not run an {@code AnomalyDetector} of its own; alert state
 * is sourced from the {@code anomaly-alerts} Kafka topic via {@code AlertSubscriber}.
 * The S3 archival worker runs only inside the ingestion process — running it in two
 * places would cause double-deletes during rebalance.
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
