package com.loginsight.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loginsight.anomaly.AnomalyDetector;
import com.loginsight.common.LogEntry;
import com.loginsight.storage.MetricsAggregator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process analytics pipeline that mirrors what the standalone ingestion worker does,
 * allowing the API to populate Dashboard metrics and Alerts without requiring the
 * separate ingestion worker process to be running.
 *
 * <p>Subscribes to the raw-logs topic (own consumer group so it does not compete with
 * LogBuffer), feeds each entry through {@link AnomalyDetector} and {@link MetricsAggregator},
 * and delivers alerts directly into {@link AlertSubscriber}'s in-memory cache.
 *
 * <p>{@link MetricsAggregator} caches the latest snapshot per service in memory on every
 * flush interval, making it available to {@link LogQueryService} as a fallback when
 * InfluxDB is not configured or unreachable.
 */
@Component
public class InProcessPipeline {

    private static final Logger log = LoggerFactory.getLogger(InProcessPipeline.class);

    private final AlertSubscriber alertSubscriber;
    private final MetricsAggregator metricsAggregator;
    private final AnomalyDetector anomalyDetector;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final String bootstrapServers;
    private final String rawTopic;
    private final boolean enabled;
    private KafkaConsumer<String, String> consumer;

    public InProcessPipeline(
            AlertSubscriber alertSubscriber,
            MetricsAggregator metricsAggregator,
            @Value("${loginsight.kafka.bootstrap-servers:}") String bootstrapServers,
            @Value("${loginsight.kafka.raw-topic:raw-logs}") String rawTopic,
            @Value("${loginsight.kafka.enabled:true}") boolean kafkaEnabled) {
        this.alertSubscriber    = alertSubscriber;
        this.metricsAggregator  = metricsAggregator;
        this.bootstrapServers   = bootstrapServers;
        this.rawTopic           = rawTopic;
        this.enabled            = kafkaEnabled && bootstrapServers != null && !bootstrapServers.isBlank();
        this.anomalyDetector    = new AnomalyDetector(alert -> {
            log.warn("In-process anomaly: {}", alert.toSummary());
            alertSubscriber.addAlert(alert);
            metricsAggregator.recordAnomaly(alert.service());
        });
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.warn("InProcessPipeline disabled — Kafka not configured");
            return;
        }
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,        bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG,                 "loginsight-pipeline-" + UUID.randomUUID());
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,        "latest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,       "true");
        this.consumer = new KafkaConsumer<>(p);
        running.set(true);
        metricsAggregator.start();
        Thread.ofVirtual().name("in-process-pipeline").start(this::pollLoop);
        log.info("InProcessPipeline started — topic='{}'", rawTopic);
    }

    private void pollLoop() {
        try {
            consumer.subscribe(List.of(rawTopic));
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    try {
                        LogEntry entry = mapper.readValue(r.value(), LogEntry.class);
                        anomalyDetector.ingest(entry);
                        metricsAggregator.record(entry);
                    } catch (Exception e) {
                        log.warn("Failed to process log entry in pipeline: {}", e.getMessage());
                    }
                }
            }
        } catch (WakeupException ignored) {
        } catch (Exception e) {
            log.error("InProcessPipeline poll loop crashed: {}", e.getMessage(), e);
        } finally {
            try { consumer.close(); } catch (Exception ignored) {}
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumer != null) consumer.wakeup();
    }
}
