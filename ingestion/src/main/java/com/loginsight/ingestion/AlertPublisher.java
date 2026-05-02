package com.loginsight.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loginsight.common.AlertEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

/**
 * Publishes {@link AlertEvent} records to the {@code anomaly-alerts} Kafka topic
 * so the API process (and any downstream notification system) can consume them.
 *
 * <p>Idempotent producer with {@code acks=all} for durable, deduplicated writes.
 */
public final class AlertPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AlertPublisher.class);

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper;
    private final String topic;

    public AlertPublisher() {
        this.topic  = System.getenv().getOrDefault("ALERTS_TOPIC", "anomaly-alerts");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,    requireEnv("KAFKA_BOOTSTRAP_SERVERS"));
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,   "true");
        p.put(ProducerConfig.ACKS_CONFIG,                 "all");
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        p.put(ProducerConfig.RETRIES_CONFIG,              Integer.MAX_VALUE);
        p.put(ProducerConfig.CLIENT_ID_CONFIG,            "loginsight-alert-publisher-" + UUID.randomUUID());

        this.producer = new KafkaProducer<>(p);
        log.info("AlertPublisher ready — topic='{}'", topic);
    }

    public void publish(AlertEvent alert) {
        try {
            String json = mapper.writeValueAsString(alert);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, alert.service(), json);
            producer.send(record, (md, ex) -> {
                if (ex != null) log.error("Failed to publish alert {}: {}", alert.alertId(), ex.getMessage());
            });
        } catch (Exception e) {
            log.error("Failed to serialise alert {}: {}", alert.alertId(), e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            producer.flush();
            producer.close(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("Error closing AlertPublisher: {}", e.getMessage());
        }
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException("Required env var not set: " + name);
        return v;
    }
}
