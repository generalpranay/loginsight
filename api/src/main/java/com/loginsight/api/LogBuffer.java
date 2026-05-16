package com.loginsight.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loginsight.common.LogEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory log store that serves two purposes:
 *
 * <ol>
 *   <li><strong>Kafka subscriber</strong> — subscribes to the raw-logs topic (same one the
 *       simulator publishes to) so that simulator output is immediately visible in the
 *       {@code GET /api/v1/logs} endpoint without requiring Elasticsearch to be running.</li>
 *   <li><strong>Manual entry sink</strong> — {@link #add(LogEntry)} lets the REST layer
 *       insert hand-crafted log entries submitted via the web UI.</li>
 * </ol>
 *
 * <p>A bounded deque caps memory: the oldest entries are evicted once {@code MAX_SIZE} is
 * reached. The Kafka consumer uses {@code auto.offset.reset=latest} so it only captures
 * messages produced after the API started — it does not replay historical log traffic.
 */
@Component
public class LogBuffer {

    private static final Logger log = LoggerFactory.getLogger(LogBuffer.class);
    private static final int MAX_SIZE = 50_000;

    private final ConcurrentLinkedDeque<LogEntry> buffer = new ConcurrentLinkedDeque<>();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final String bootstrapServers;
    private final String rawTopic;
    private final boolean enabled;
    private KafkaConsumer<String, String> consumer;

    public LogBuffer(
            @Value("${loginsight.kafka.bootstrap-servers:}") String bootstrapServers,
            @Value("${loginsight.kafka.raw-topic:raw-logs}") String rawTopic,
            @Value("${loginsight.kafka.enabled:true}") boolean kafkaEnabled) {
        this.bootstrapServers = bootstrapServers;
        this.rawTopic         = rawTopic;
        this.enabled          = kafkaEnabled && bootstrapServers != null && !bootstrapServers.isBlank();
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.warn("LogBuffer disabled — Kafka not configured; only manual entries will be stored");
            return;
        }
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,        bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG,                 "loginsight-logbuffer-" + UUID.randomUUID());
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,        "latest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,       "true");
        this.consumer = new KafkaConsumer<>(p);
        running.set(true);
        Thread.ofVirtual().name("log-buffer-sub").start(this::pollLoop);
        log.info("LogBuffer subscribed to topic '{}' on {}", rawTopic, bootstrapServers);
    }

    private void pollLoop() {
        try {
            consumer.subscribe(List.of(rawTopic));
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    try {
                        add(mapper.readValue(r.value(), LogEntry.class));
                    } catch (Exception e) {
                        log.warn("Failed to deserialize log entry from Kafka: {}", e.getMessage());
                    }
                }
            }
        } catch (WakeupException ignored) {
        } catch (Exception e) {
            log.error("LogBuffer poll loop crashed: {}", e.getMessage(), e);
        } finally {
            try { consumer.close(); } catch (Exception ignored) {}
        }
    }

    /** Inserts an entry at the front of the buffer, evicting the oldest if over capacity. */
    public void add(LogEntry entry) {
        buffer.addFirst(entry);
        while (buffer.size() > MAX_SIZE) buffer.pollLast();
    }

    /**
     * Returns entries matching the given filters, sorted newest-first.
     * All parameters are applied with the same semantics as the Elasticsearch query.
     */
    public List<LogEntry> query(String service, Instant from, Instant to, int limit) {
        return buffer.stream()
                .filter(e -> service == null || e.service().equals(service))
                .filter(e -> !e.timestamp().isBefore(from) && !e.timestamp().isAfter(to))
                .sorted(Comparator.comparing(LogEntry::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    public int size() { return buffer.size(); }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumer != null) consumer.wakeup();
    }
}
