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
 * Publishes {@link AlertEvent} records to the {@code anomaly-alerts} Kafka topic,
 * making them available to the API process and any downstream notification system.
 *
 * <h2>Producer configuration</h2>
 * <ul>
 *   <li><strong>Idempotent producer</strong> ({@code enable.idempotence=true}) — the broker
 *       assigns a producer ID and sequence number to each message, detecting and discarding
 *       exact duplicates that arise from producer retries. This prevents double-alerts if the
 *       network drops an ACK after the broker already committed the record.</li>
 *   <li><strong>{@code acks=all}</strong> — the broker only acknowledges the write after all
 *       in-sync replicas have persisted the record, ensuring no alert is silently lost on a
 *       leader failover.</li>
 *   <li><strong>Partition key = service name</strong> — alerts for the same service always land
 *       on the same partition, preserving order and enabling efficient per-service filtering on
 *       the consumer side.</li>
 * </ul>
 *
 * <p>The topic name defaults to {@code anomaly-alerts} and can be overridden via the
 * {@code ALERTS_TOPIC} environment variable.
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

    /**
     * Serialises the alert to JSON and sends it asynchronously to the alerts topic.
     *
     * <p>The send is fire-and-forget at the call site (non-blocking), but the callback
     * logs any broker-level delivery failure so it is visible in the application logs.
     * Serialization failures are also caught here to prevent a broken alert from
     * propagating an exception back into the hot ingestion path.
     *
     * @param alert the anomaly alert produced by {@link com.loginsight.anomaly.AnomalyDetector}
     */
    public void publish(AlertEvent alert) {
        try {
            String json = mapper.writeValueAsString(alert);
            // Use service name as partition key so alerts for the same service stay ordered
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, alert.service(), json);
            producer.send(record, (md, ex) -> {
                if (ex != null) log.error("Failed to publish alert {}: {}", alert.alertId(), ex.getMessage());
            });
        } catch (Exception e) {
            log.error("Failed to serialise alert {}: {}", alert.alertId(), e.getMessage());
        }
    }

    /**
     * Flushes any buffered records and closes the producer, waiting up to 10 seconds
     * for in-flight sends to complete before forcing a close.
     */
    @Override
    public void close() {
        try {
            // flush() blocks until all pending sends have been acknowledged or failed
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
