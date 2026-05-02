package com.loginsight.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the <strong>Log-to-Insight</strong> REST API process.
 *
 * <h2>System Architecture</h2>
 * <p>Log-to-Insight is a real-time log analytics platform built on a two-process model:
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  INGESTION PROCESS  (com.loginsight.ingestion.IngestionApplication) │
 * │                                                                     │
 * │  Kafka raw-logs ──► KafkaLogConsumer                                │
 * │                          │                                          │
 * │                    ┌─────┴──────────────────┐                       │
 * │                    ▼                        ▼                       │
 * │              AnomalyDetector        MetricsAggregator               │
 * │              (sliding window)       (per-service counters)          │
 * │                    │                        │                       │
 * │                    ▼                        ▼                       │
 * │              AlertPublisher          InfluxDbWriter                 │
 * │              (anomaly-alerts         (metrics snapshots             │
 * │               Kafka topic)            every N seconds)              │
 * │                                                                     │
 * │  KafkaLogConsumer ──► ElasticsearchWriter  (bulk index, EOS)        │
 * │  S3ArchivalWorker  (24h schedule: ES → S3 NDJSON + index delete)    │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  API PROCESS  (this class)                                          │
 * │                                                                     │
 * │  HTTP GET /api/v1/logs     ──► LogQueryService ──► ElasticsearchWriter │
 * │  HTTP GET /api/v1/metrics  ──► LogQueryService ──► InfluxDbWriter   │
 * │  HTTP GET /api/v1/alerts   ──► AlertSubscriber (in-memory cache)    │
 * │                                     ▲                               │
 * │                               Kafka anomaly-alerts topic            │
 * └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Key Design Decisions</h2>
 * <ul>
 *   <li><strong>Virtual threads</strong> — {@code spring.threads.virtual.enabled=true} replaces
 *       Tomcat's fixed thread pool with Project Loom virtual threads, achieving high I/O concurrency
 *       without reactive programming complexity.</li>
 *   <li><strong>Separation of concerns</strong> — storage classes ({@code ElasticsearchWriter},
 *       {@code InfluxDbWriter}) carry no Spring annotations so the ingestion process can use them
 *       as plain JAR dependencies without pulling in the Spring container.</li>
 *   <li><strong>Graceful degradation</strong> — all three backends (Elasticsearch, InfluxDB, Kafka)
 *       disable themselves with a warning when their connection URL is not configured, allowing the
 *       API to start cleanly in local development without external services running.</li>
 * </ul>
 */
@SpringBootApplication
public class LogInsightApplication {
    public static void main(String[] args) {
        SpringApplication.run(LogInsightApplication.class, args);
    }
}
