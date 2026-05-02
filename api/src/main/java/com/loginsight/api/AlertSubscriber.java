package com.loginsight.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loginsight.common.AlertEvent;
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
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long-running Kafka consumer (virtual thread) that subscribes to the
 * {@code anomaly-alerts} topic and keeps the most recent alerts in memory
 * for the {@code GET /api/v1/alerts} endpoint.
 *
 * <p>A bounded deque caps memory usage; the oldest alerts are dropped when the
 * cap is reached. For production, alerts should be persisted (e.g. ES index
 * {@code alerts-*}) — this class is enough to wire end-to-end on the demo path.
 */
@Component
public class AlertSubscriber {

    private static final Logger log = LoggerFactory.getLogger(AlertSubscriber.class);
    private static final int MAX_CACHED_ALERTS = 10_000;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Deque<AlertEvent> recent = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch latch = new CountDownLatch(1);

    private final String bootstrapServers;
    private final String topic;
    private final boolean enabled;
    private KafkaConsumer<String, String> consumer;

    public AlertSubscriber(
            @Value("${loginsight.kafka.bootstrap-servers:}") String bootstrapServers,
            @Value("${loginsight.kafka.alerts-topic:anomaly-alerts}") String topic,
            @Value("${loginsight.kafka.enabled:true}") boolean enabled) {
        this.bootstrapServers = bootstrapServers;
        this.topic            = topic;
        this.enabled          = enabled && bootstrapServers != null && !bootstrapServers.isBlank();
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.warn("AlertSubscriber disabled — set loginsight.kafka.bootstrap-servers to enable");
            latch.countDown();
            return;
        }

        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,         bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG,                  "loginsight-api-" + UUID.randomUUID());
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,    StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,  StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,         "latest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,        "true");
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,           "read_committed");

        this.consumer = new KafkaConsumer<>(p);
        running.set(true);
        Thread.ofVirtual().name("alert-subscriber").start(this::pollLoop);
    }

    private void pollLoop() {
        try {
            consumer.subscribe(List.of(topic));
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    try {
                        AlertEvent alert = mapper.readValue(r.value(), AlertEvent.class);
                        recent.addFirst(alert);
                        while (recent.size() > MAX_CACHED_ALERTS) recent.pollLast();
                    } catch (Exception e) {
                        log.warn("Failed to parse alert message: {}", e.getMessage());
                    }
                }
            }
        } catch (WakeupException ignored) {
            // expected on shutdown
        } catch (Exception e) {
            log.error("AlertSubscriber crashed: {}", e.getMessage(), e);
        } finally {
            try { consumer.close(); } catch (Exception ignored) {}
            latch.countDown();
        }
    }

    public List<AlertEvent> getRecent() {
        return recent.stream()
                .sorted(Comparator.comparing(AlertEvent::detectedAt).reversed())
                .toList();
    }

    public List<AlertEvent> getForService(String service) {
        return recent.stream()
                .filter(a -> a.service().equals(service))
                .sorted(Comparator.comparing(AlertEvent::detectedAt).reversed())
                .toList();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumer != null) consumer.wakeup();
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
