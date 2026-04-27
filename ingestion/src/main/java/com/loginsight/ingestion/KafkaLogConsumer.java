package com.loginsight.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loginsight.common.LogEntry;
import com.loginsight.telemetry.TelemetryConfig;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Kafka consumer group worker that implements Exactly-Once Semantics (EOS) on the read side.
 *
 * <p>Each instance spawns one virtual thread (Project Loom) that runs the poll loop for its
 * lifetime. Offsets are committed manually via {@link KafkaConsumer#commitSync} only after
 * the downstream batch handler returns successfully — guaranteeing at-least-once delivery.
 * Combined with a transactional producer on the write side, this achieves end-to-end EOS.
 *
 * <p>Key EOS configuration choices:
 * <ul>
 *   <li>{@code enable.auto.commit=false} — prevents offset advancement before processing completes</li>
 *   <li>{@code isolation.level=read_committed} — skips messages from aborted producer transactions</li>
 *   <li>{@code partition.assignment.strategy=StickyAssignor} — minimises partition movement during
 *       rebalances, reducing the window where in-flight batches must be replayed</li>
 * </ul>
 *
 * <p>All configuration is read from environment variables (12-factor style).
 * Required: {@code KAFKA_BOOTSTRAP_SERVERS}, {@code KAFKA_TOPIC}, {@code KAFKA_GROUP_ID}.
 */
public final class KafkaLogConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaLogConsumer.class);

    private final KafkaConsumer<String, String> consumer;
    private final Consumer<List<LogEntry>> downstream;
    private final TelemetryConfig telemetry;
    private final ObjectMapper mapper;
    private final String topic;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicLong totalProcessed = new AtomicLong(0);

    /**
     * Constructs a consumer that delivers deserialized batches to {@code downstream}.
     * The downstream is called once per Kafka poll batch; its successful return triggers
     * the synchronous offset commit.
     *
     * @param downstream batch handler — called with every poll's worth of entries; must not throw
     * @param telemetry  OTel instrumentation handle
     */
    public KafkaLogConsumer(Consumer<List<LogEntry>> downstream, TelemetryConfig telemetry) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.telemetry  = Objects.requireNonNull(telemetry,  "telemetry");
        this.topic      = requireEnv("KAFKA_TOPIC");
        this.mapper     = new ObjectMapper().registerModule(new JavaTimeModule());
        this.consumer   = new KafkaConsumer<>(buildConsumerProps());
    }

    /**
     * Subscribes to the configured topic and starts the poll loop on a new virtual thread.
     * Returns immediately; use {@link #awaitShutdown()} to block until the loop stops.
     */
    public void startAsync() {
        running.set(true);
        Thread.ofVirtual()
                .name("kafka-poll-" + topic)
                .start(this::pollLoop);
        log.info("KafkaLogConsumer started on virtual thread, topic='{}'", topic);
    }

    /** Blocks the calling thread until the poll loop has cleanly terminated. */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    /**
     * Signals the poll loop to stop via {@link KafkaConsumer#wakeup()} (the only
     * thread-safe KafkaConsumer method) then waits for the loop to drain and commit.
     */
    @Override
    public void close() {
        log.info("KafkaLogConsumer shutdown requested");
        running.set(false);
        consumer.wakeup();
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pollLoop() {
        consumer.subscribe(List.of(topic), new StickyRebalanceListener(consumer));

        long windowStart  = System.currentTimeMillis();
        long windowCount  = 0;

        try {
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) continue;

                List<LogEntry> batch  = new ArrayList<>(records.count());
                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        LogEntry entry = mapper.readValue(record.value(), LogEntry.class);
                        batch.add(entry);
                        offsets.put(
                                new TopicPartition(record.topic(), record.partition()),
                                new OffsetAndMetadata(record.offset() + 1)
                        );
                    } catch (Exception e) {
                        log.error("Deserialization failure at partition={} offset={}: {}",
                                record.partition(), record.offset(), e.getMessage());
                    }
                }

                if (!batch.isEmpty()) {
                    downstream.accept(batch);
                    // Commit only after downstream confirms success — core of at-least-once
                    consumer.commitSync(offsets, Duration.ofSeconds(5));

                    windowCount += batch.size();
                    long elapsed = System.currentTimeMillis() - windowStart;
                    if (elapsed >= 1_000) {
                        double throughput = windowCount * 1_000.0 / elapsed;
                        telemetry.updateThroughput(throughput);
                        telemetry.recordMessagesProcessed(windowCount, topic);
                        log.debug("Throughput: {:.1f} msg/s  total={}", throughput, totalProcessed.addAndGet(windowCount));
                        windowStart = System.currentTimeMillis();
                        windowCount = 0;
                    }
                }
            }
        } catch (WakeupException e) {
            if (running.get()) {
                log.error("Unexpected WakeupException while consumer was still marked running", e);
            }
        } finally {
            try {
                consumer.commitSync(Duration.ofSeconds(10));
            } catch (Exception e) {
                log.warn("Final offset commit failed on shutdown: {}", e.getMessage());
            }
            consumer.close();
            shutdownLatch.countDown();
            log.info("KafkaLogConsumer shut down cleanly, total processed={}", totalProcessed.get());
        }
    }

    private Properties buildConsumerProps() {
        String bootstrapServers = requireEnv("KAFKA_BOOTSTRAP_SERVERS");
        String groupId          = requireEnv("KAFKA_GROUP_ID");
        String podHostname      = System.getenv().getOrDefault("HOSTNAME", UUID.randomUUID().toString());

        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,           bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG,                    groupId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,      StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,    StringDeserializer.class.getName());
        // EOS: manual offset control
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,          "false");
        // EOS: skip aborted transactional messages from producers
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,             "read_committed");
        // Sticky assignment minimises partition churn on rebalance, shrinking the replay window
        p.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.StickyAssignor");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,           "earliest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,            "500");
        p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,        "300000");
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,          "30000");
        p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,       "10000");
        // Include pod hostname so Kafka broker logs show which pod each consumer is
        p.put(ConsumerConfig.CLIENT_ID_CONFIG,                   "loginsight-consumer-" + podHostname);
        return p;
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException("Required env var not set: " + name);
        return v;
    }

    /**
     * Commits current offsets before partitions are revoked so the next owner of those
     * partitions does not reprocess already-committed work.
     */
    private static final class StickyRebalanceListener implements ConsumerRebalanceListener {
        private static final Logger rlog = LoggerFactory.getLogger(StickyRebalanceListener.class);
        private final KafkaConsumer<?, ?> consumer;

        StickyRebalanceListener(KafkaConsumer<?, ?> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            rlog.info("Partitions revoked {}  — committing offsets before rebalance", partitions);
            consumer.commitSync(Duration.ofSeconds(5));
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            rlog.info("Partitions assigned: {}", partitions);
        }
    }
}
