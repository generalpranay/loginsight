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

import java.time.Duration;
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
 * Run standalone:
 *   KAFKA_BOOTSTRAP_SERVERS=localhost:9092 mvn exec:java \
 *     -pl ingestion -Dexec.mainClass=com.loginsight.ingestion.LogSimulator
 *
 * Or control programmatically:
 *   LogSimulator sim = new LogSimulator("localhost:9092");
 *   sim.start("raw-logs");  // non-blocking
 *   // ...
 *   sim.stop();
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
    /** Error spike fires after this many seconds on first start, then every SPIKE_INTERVAL_SEC. */
    private static final long SPIKE_INITIAL_DELAY_SEC = 20;
    /** Repeat interval between spikes. */
    private static final long SPIKE_INTERVAL_SEC = 90;
    private static final long SPIKE_DURATION_SEC = 15;
    /** Events per second during a spike (high enough to exceed 6× baseline). */
    private static final int SPIKE_MSGS_PER_SEC   = 80;

    // ── Instance state ────────────────────────────────────────────────────────
    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper;
    private final Random rng = new Random();
    private final AtomicLong totalSent = new AtomicLong();
    private volatile boolean spikeActive = false;
    private volatile boolean spikeEnabled = true;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile Instant startedAt;

    // ── Static helpers ────────────────────────────────────────────────────────

    /** Returns a copy of the simulated service names (for use by the web UI). */
    public static String[] getServices() {
        return SERVICES.clone();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Standalone entry point.
     *
     * <pre>
     * Environment variables (always respected):
     *   KAFKA_BOOTSTRAP_SERVERS  default: localhost:9092
     *   KAFKA_TOPIC              default: raw-logs
     *
     * CLI flags:
     *   --count N    Send exactly N messages then exit cleanly. Omit for continuous mode.
     *   --no-spike   Suppress the periodic error-spike pattern (useful for baseline load tests).
     * </pre>
     */
    public static void main(String[] args) throws InterruptedException {
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topic     = System.getenv().getOrDefault("KAFKA_TOPIC", "raw-logs");

        long count    = 0;       // 0 = run until SIGTERM
        boolean noSpike = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--count"    -> count    = Long.parseLong(args[++i]);
                case "--no-spike" -> noSpike  = true;
                default           -> log.warn("Unknown arg ignored: {}", args[i]);
            }
        }

        log.info("LogSimulator starting — broker={} topic={} count={} spike={}",
                bootstrap, topic, count > 0 ? count : "unlimited", !noSpike);

        LogSimulator sim = new LogSimulator(bootstrap);
        if (noSpike) sim.disableSpike();

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutting down simulator — total sent: {}", sim.getTotalSent());
            sim.stop();
        }));

        sim.start(topic);

        if (count > 0) {
            while (sim.getTotalSent() < count) {
                Thread.sleep(100);
            }
            log.info("Target count {} reached — stopping", count);
            sim.stop();
        } else {
            Thread.currentThread().join(); // block until SIGTERM
        }
    }

    public LogSimulator(String bootstrapServers) {
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

    /**
     * Starts the simulator in background virtual threads. Returns immediately.
     * Call {@link #stop()} to shut down cleanly.
     *
     * @throws IllegalStateException if already running
     */
    public synchronized void start(String topic) {
        if (running) throw new IllegalStateException("Simulator is already running");

        scheduler = Executors.newScheduledThreadPool(
                USERS.length + 2, Thread.ofVirtual().name("sim-", 0).factory()
        );

        long delayMicros = 1_000_000L / MSGS_PER_USER_PER_SEC;
        for (String user : USERS) {
            scheduler.scheduleAtFixedRate(
                    () -> sendNormalEvent(topic, user),
                    rng.nextInt(500), delayMicros / 1000, TimeUnit.MILLISECONDS
            );
        }

        if (spikeEnabled) {
            scheduler.scheduleAtFixedRate(
                    () -> runSpike(topic),
                    SPIKE_INITIAL_DELAY_SEC, SPIKE_INTERVAL_SEC, TimeUnit.SECONDS
            );
        }

        scheduler.scheduleAtFixedRate(
                () -> log.info("--- Simulator stats: total_sent={} spike_active={}", totalSent.get(), spikeActive),
                10, 10, TimeUnit.SECONDS
        );

        running = true;
        startedAt = Instant.now();
        log.info("Simulator running. {} users × {} services. First spike on '{}' in {}s, then every {}s.",
                USERS.length, SERVICES.length, SPIKE_SERVICE, SPIKE_INITIAL_DELAY_SEC, SPIKE_INTERVAL_SEC);
    }

    /** Stops the simulator and closes the Kafka producer. This instance cannot be restarted. */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        spikeActive = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        try { producer.flush(); producer.close(); } catch (Exception ignored) {}
        log.info("Simulator stopped — total sent: {}", totalSent.get());
    }

    // ── Status accessors ──────────────────────────────────────────────────────

    public boolean isRunning()     { return running; }
    public long    getTotalSent()  { return totalSent.get(); }
    public boolean isSpikeActive() { return spikeActive; }

    /** Disables the periodic error-spike pattern. Must be called before {@link #start(String)}. */
    public void disableSpike()     { this.spikeEnabled = false; }

    /** Seconds until the next spike, or -1 if stopped / spike currently active. */
    public long getNextSpikeInSeconds() {
        if (!running || spikeActive || startedAt == null) return -1;
        long elapsed = Duration.between(startedAt, Instant.now()).toSeconds();
        if (elapsed < SPIKE_INITIAL_DELAY_SEC) return SPIKE_INITIAL_DELAY_SEC - elapsed;
        long sinceFirst = elapsed - SPIKE_INITIAL_DELAY_SEC;
        return SPIKE_INTERVAL_SEC - (sinceFirst % SPIKE_INTERVAL_SEC);
    }

    // ── Private simulation logic ──────────────────────────────────────────────

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

        while (System.currentTimeMillis() < deadline && running) {
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
