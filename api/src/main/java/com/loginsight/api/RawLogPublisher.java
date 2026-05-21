package com.loginsight.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loginsight.common.LogEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

/**
 * Publishes manually-submitted {@link LogEntry} records to the raw-logs Kafka topic so that
 * entries arriving via {@code POST /api/v1/logs} flow through the full ingestion pipeline
 * (AnomalyDetector → MetricsAggregator → ElasticsearchWriter) rather than landing only in
 * the in-memory {@link LogBuffer}.
 *
 * <p>Gracefully degrades to a no-op when Kafka is not configured, matching the same pattern
 * used by {@link LogBuffer} and {@link AlertSubscriber}.
 */
@Component
public class RawLogPublisher {

    private static final Logger log = LoggerFactory.getLogger(RawLogPublisher.class);

    private final String bootstrapServers;
    private final String rawTopic;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private KafkaProducer<String, String> producer;

    public RawLogPublisher(
            @Value("${loginsight.kafka.bootstrap-servers:}") String bootstrapServers,
            @Value("${loginsight.kafka.raw-topic:raw-logs}") String rawTopic,
            @Value("${loginsight.kafka.enabled:true}") boolean kafkaEnabled) {
        this.bootstrapServers = bootstrapServers;
        this.rawTopic         = rawTopic;
        this.enabled          = kafkaEnabled && bootstrapServers != null && !bootstrapServers.isBlank();
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.warn("RawLogPublisher disabled — Kafka not configured; POST /api/v1/logs will not publish to the pipeline");
            return;
        }
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,                bootstrapServers);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,             StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,           StringSerializer.class.getName());
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,               "true");
        p.put(ProducerConfig.ACKS_CONFIG,                             "all");
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,   "5");
        p.put(ProducerConfig.RETRIES_CONFIG,                          Integer.MAX_VALUE);
        p.put(ProducerConfig.CLIENT_ID_CONFIG,                        "loginsight-raw-log-publisher-" + UUID.randomUUID());
        this.producer = new KafkaProducer<>(p);
        log.info("RawLogPublisher ready — topic='{}' on {}", rawTopic, bootstrapServers);
    }

    /**
     * Serialises the entry to JSON and sends it asynchronously to the raw-logs topic.
     * Uses the service name as the partition key to keep logs for the same service ordered.
     * Does nothing if Kafka is not configured.
     */
    public void publish(LogEntry entry) {
        if (!enabled || producer == null) return;
        try {
            String json = mapper.writeValueAsString(entry);
            ProducerRecord<String, String> record = new ProducerRecord<>(rawTopic, entry.service(), json);
            producer.send(record, (md, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish log entry {} to '{}': {}", entry.id(), rawTopic, ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Failed to serialise log entry {}: {}", entry.id(), e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (producer == null) return;
        try {
            producer.flush();
            producer.close(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("Error closing RawLogPublisher: {}", e.getMessage());
        }
    }
}
