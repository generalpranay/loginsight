package com.loginsight.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loginsight.common.LogEntry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Development/test simulator that publishes realistic log traffic to the raw-logs Kafka topic.
 *
 * Simulates 5 services x 20 virtual users, with a periodic error spike on one service
 * so the AnomalyDetector fires within a few minutes of startup.
 *
 * Run with:
 *   KAFKA_BOOTSTRAP_SERVERS=localhost:9092 mvn exec:java \
 *     -pl ingestion -Dexec.mainClass=com.loginsight.ingestion.LogSimulator
 */
public final class LogSimulator {

    private static final Logger log = LoggerFactory.getLogger(LogSimulator.class);

    // ── Services and their users ──────────────────────────────────────────────
    private static final String[] SERVICES = {
            "auth-service", "checkout-service", "payment-service",
            "search-service", "user-service"
    };

    private static final String[] USERS = {
            "user-001", "user-002", "user-003", "user-004", "user-005",
            "user-006", "user-007", "user-008", "user-009", "user-010",
            "user-011", "user-012", "user-013", "user-014", "user-015",
            "user-016", "user-017", "user-018", "user-019", "user-020"
    };

    private static final String[] HOSTS = {
            "pod-a1b2", "pod-c3d4", "pod-e5f6", "pod-g7h8", "pod-i9j0"
    };

    // Normal traffic: weighted toward 200s, small tail of errors
    private static final int[][] NORMAL_STATUS_WEIGHTS = {
            {200, 60}, {201, 15}, {400, 8}, {401, 5}, {404, 7}, {500, 3}, {503, 2}
    };

    // Spike traffic: intentionally heavy 500s to trigger anomaly detector
    private static final int[][] SPIKE_STATUS_WEIGHTS = {
            {500, 70}, {503, 20}, {200, 10}
    };

    private static final String[] LOG_MESSAGES_200 = {
            "Request completed successfully", "Resource retrieved", "Operation committed",
            "Cache hit — fast path", "Auth token validated", "Payment processed"
    };
    private static final String[] LOG_MESSAGES_4XX = {
            "Invalid request payload", "Authentication required", "Resource not found",
            "Rate limit exceeded", "Malformed query parameter"
    };
    private static final String[] LOG_MESSAGES_5XX = {
            "Database connection timeout", "Downstream service unavailable",
            "Unhandled exception in handler", "Circuit breaker open", "Memory pressure — GC stall"
    };

    // ── Tuning ────────────────────────────────────────────────────────────────
    /** Messages per second per virtual user during normal traffic. */
    private static final int MSGS_PER_USER_PER_SEC = 2;
    /** Which service gets spiked. */
    private static final String SPIKE_SERVICE = "payment-service";
    /** Error spike fires every N seconds and lasts M seconds. */
    private static final long SPIKE_INTERVAL_SEC = 90;
    private static final long SPIKE_DURATION_SEC = 15;
    /** Events per second during a spike (high enough to exceed 6× baseline). */
    private static final int SPIKE_MSGS_PER_SEC   = 80;

    // ── State ─────────────────────────────────────────────────────────────────
    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper;
    private final Random rng = new Random();
    private final AtomicLong totalSent = new AtomicLong();
    private volatile boolean spikeActive = false;

    public static void main(String[] args) throws Exception {
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topic     = System.getenv().getOrDefault("KAFKA_TOPIC", "raw-logs");
        log.info("LogSimulator starting — broker={} topic={}", bootstrap, topic);
        new LogSimulator(bootstrap).run(topic);
    }

    LogSimulator(String bootstrapServers) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,       bootstrapServers);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,    StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,  StringSerializer.class.getName());
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,      "true");
        p.put(ProducerConfig.ACKS_CONFIG,                    "all");
        p.put(ProducerConfig.LINGER_MS_CONFIG,               "5");
        p.put(ProducerConfig.BATCH_SIZE_CONFIG,              "65536");
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,        "snappy");
        this.producer = new KafkaProducer<>(p);
        this.mapper   = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private void run(String topic) throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
                USERS.length + 2, Thread.ofVirtual().name("sim-", 0).factory()
        );

        // Normal traffic: each user sends at MSGS_PER_USER_PER_SEC
        long delayMicros = 1_000_000L / MSGS_PER_USER_PER_SEC;
        for (String user : USERS) {
            scheduler.scheduleAtFixedRate(
                    () -> sendNormalEvent(topic, user),
                    rng.nextInt(500), delayMicros / 1000, TimeUnit.MILLISECONDS
            );
        }

        // Spike scheduler: fires every SPIKE_INTERVAL_SEC
        scheduler.scheduleAtFixedRate(
                () -> runSpike(topic),
                SPIKE_INTERVAL_SEC, SPIKE_INTERVAL_SEC, TimeUnit.SECONDS
        );

        // Stats reporter
        scheduler.scheduleAtFixedRate(
                () -> log.info("--- Simulator stats: total_sent={} spike_active={}", totalSent.get(), spikeActive),
                10, 10, TimeUnit.SECONDS
        );

        log.info("Simulator running. {} users × {} services. Spike on '{}' every {}s.",
                USERS.length, SERVICES.length, SPIKE_SERVICE, SPIKE_INTERVAL_SEC);
        log.info("Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutting down simulator — total sent: {}", totalSent.get());
            scheduler.shutdown();
            producer.flush();
            producer.close();
        }));

        scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
    }

    private void sendNormalEvent(String topic, String user) {
        String service = SERVICES[rng.nextInt(SERVICES.length)];
        int    status  = weightedPick(NORMAL_STATUS_WEIGHTS);
        send(topic, buildEntry(service, user, status));
    }

    private void runSpike(String topic) {
        spikeActive = true;
        log.warn("SPIKE START on '{}' — sending {} msg/s for {}s",
                SPIKE_SERVICE, SPIKE_MSGS_PER_SEC, SPIKE_DURATION_SEC);

        long deadline = System.currentTimeMillis() + SPIKE_DURATION_SEC * 1000;
        long sleepMs  = 1_000L / SPIKE_MSGS_PER_SEC;

        while (System.currentTimeMillis() < deadline) {
            int status = weightedPick(SPIKE_STATUS_WEIGHTS);
            send(topic, buildEntry(SPIKE_SERVICE, USERS[rng.nextInt(USERS.length)], status));
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }

        spikeActive = false;
        log.warn("SPIKE END on '{}'", SPIKE_SERVICE);
    }

    private void send(String topic, LogEntry entry) {
        try {
            String json = mapper.writeValueAsString(entry);
            producer.send(new ProducerRecord<>(topic, entry.service(), json), (md, ex) -> {
                if (ex != null) log.error("Send failed: {}", ex.getMessage());
            });
            totalSent.incrementAndGet();
        } catch (Exception e) {
            log.error("Serialization error: {}", e.getMessage());
        }
    }

    private LogEntry buildEntry(String service, String user, int status) {
        String level = status >= 500 ? "ERROR" : status >= 400 ? "WARN" : "INFO";
        String msg   = status >= 500 ? LOG_MESSAGES_5XX[rng.nextInt(LOG_MESSAGES_5XX.length)]
                     : status >= 400 ? LOG_MESSAGES_4XX[rng.nextInt(LOG_MESSAGES_4XX.length)]
                     :                 LOG_MESSAGES_200[rng.nextInt(LOG_MESSAGES_200.length)];
        String host  = HOSTS[rng.nextInt(HOSTS.length)];
        Map<String, String> tags = Map.of("user", user, "env", "test");
        return new LogEntry(
                UUID.randomUUID().toString(),
                service, level, status, msg, host,
                UUID.randomUUID().toString().replace("-", ""),
                Instant.now(), tags
        );
    }

    private int weightedPick(int[][] weights) {
        int total = Arrays.stream(weights).mapToInt(w -> w[1]).sum();
        int roll  = rng.nextInt(total);
        int cum   = 0;
        for (int[] w : weights) {
            cum += w[1];
            if (roll < cum) return w[0];
        }
        return weights[0][0];
    }
}
