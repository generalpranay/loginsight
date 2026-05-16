package com.loginsight.api;

import com.loginsight.ingestion.LogSimulator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Spring-managed wrapper that controls the {@link LogSimulator} lifecycle from the API process.
 *
 * <p>Each {@link #start()} call creates a fresh {@code LogSimulator} instance (and a new
 * Kafka producer). The previous instance's producer is already closed by {@link #stop()},
 * so there is no resource leak.
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    @Value("${loginsight.kafka.bootstrap-servers:}")
    private String bootstrapServers;

    @Value("${loginsight.kafka.raw-topic:raw-logs}")
    private String rawTopic;

    private volatile LogSimulator simulator;

    public synchronized void start() {
        if (simulator != null && simulator.isRunning()) {
            throw new IllegalStateException("Simulation is already running");
        }
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalStateException(
                "Kafka bootstrap servers not configured — set the LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS environment variable");
        }
        log.info("Starting log simulator — broker={} topic={}", bootstrapServers, rawTopic);
        simulator = new LogSimulator(bootstrapServers);
        simulator.start(rawTopic);
    }

    public synchronized void stop() {
        if (simulator != null && simulator.isRunning()) {
            log.info("Stopping log simulator");
            simulator.stop();
        }
    }

    public Map<String, Object> getStatus() {
        boolean running = simulator != null && simulator.isRunning();
        return Map.of(
            "running",            running,
            "totalSent",          simulator != null ? simulator.getTotalSent() : 0L,
            "spikeActive",        simulator != null && simulator.isSpikeActive(),
            "nextSpikeInSeconds", simulator != null ? simulator.getNextSpikeInSeconds() : -1L,
            "bootstrapServers",   bootstrapServers != null ? bootstrapServers : "",
            "topic",              rawTopic != null ? rawTopic : "raw-logs"
        );
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }
}
