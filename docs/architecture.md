# System Architecture

## C4 Container Diagram

```mermaid
C4Container
    title Log-to-Insight Observability Platform

    Person(user, "Engineer / SRE", "Queries logs, views dashboards, responds to alerts")

    System_Boundary(loginsight, "Log-to-Insight Platform") {

        Container(producers, "Log Producers", "Any service", "Emit structured JSON log events to Kafka via a transactional producer")

        Container(kafka, "Apache Kafka", "MSK 3.5 / cp-kafka 7.5", "Durable, ordered event stream. Topic: raw-logs (6 partitions). EOS via transaction.id per pod.")

        Container(ingestion, "Ingestion Workers", "Java 21 / Virtual Threads\n(3 K8s replicas)", "KafkaLogConsumer: polls raw-logs with isolation.level=read_committed, StickyAssignor. Calls AnomalyDetector + ElasticsearchWriter per batch. Commits offsets only after both writes succeed.")

        Container(anomaly, "AnomalyDetector", "Java 21 / in-process", "5-minute sliding window (five 1-minute buckets) per (service, statusCode) pair. Fires AlertEvent when current bucket ≥ 6× baseline average (500% spike).")

        ContainerDb(elasticsearch, "Elasticsearch", "ES 8.11 / ILM hot tier", "Raw log documents. Index pattern: logs-YYYY.MM.dd. Hot for 0–7 days. Queried by the REST API.")

        ContainerDb(influxdb, "InfluxDB", "InfluxDB 2.7", "Time-series metrics: messages_per_second, error_rate, anomaly_count. Written by ingestion workers, queried by REST API.")

        Container(s3worker, "S3ArchivalWorker", "Java 21 virtual thread\n(scheduled daily)", "Pages through ES indices older than 7 days, serialises to NDJSON, uploads to S3, then deletes the ES index.")

        ContainerDb(s3, "AWS S3", "S3 + Glacier lifecycle", "Cold log archive. Logs transition to Glacier after 90 days, expire after 7 years.")

        Container(api, "REST API", "Spring Boot 3.2\nVirtual Threads", "GET /logs  GET /alerts  GET /metrics/summary. Queries ES and InfluxDB. Runs on virtual threads — no reactive stack needed.")

        Container(telemetry, "OpenTelemetry SDK", "otel-java 1.33\nPrometheus exporter", "Custom counters (messages_processed, anomaly_detected) and gauges (throughput, active_anomalies). Exported via Prometheus pull on :9464/:9465.")

        Container(prometheus, "Prometheus", "prom/prometheus 2.48", "Scrapes all OTel Prometheus endpoints. Stores metrics time-series.")

        Container(grafana, "Grafana", "grafana 10.2", "Dashboards over Prometheus + InfluxDB. Pre-provisioned datasources.")
    }

    Rel(user, api, "HTTP/JSON", "GET /logs, /alerts, /metrics/summary")
    Rel(user, grafana, "Browser", "Dashboards")
    Rel(producers, kafka, "Kafka producer API", "transactional, TLS")
    Rel(kafka, ingestion, "Kafka consumer API", "read_committed, StickyAssignor, manual commit")
    Rel(ingestion, anomaly, "in-process call", "per-batch")
    Rel(ingestion, elasticsearch, "Bulk Index API", "HTTPS")
    Rel(ingestion, influxdb, "InfluxDB write API", "HTTP")
    Rel(ingestion, telemetry, "OTel API", "in-process")
    Rel(s3worker, elasticsearch, "Search + Delete API", "HTTPS, daily")
    Rel(s3worker, s3, "PutObject", "AWS SDK v2, TLS")
    Rel(api, elasticsearch, "Search API", "HTTPS")
    Rel(api, influxdb, "Flux query", "HTTP")
    Rel(telemetry, prometheus, "HTTP pull /metrics", ":9464/:9465")
    Rel(prometheus, grafana, "PromQL", "HTTP")
```

## Data Flow Summary

```
Log Producer
    │  (transactional Kafka producer, EOS write-side)
    ▼
Kafka Topic: raw-logs  (6 partitions, retention=7d)
    │  (read_committed isolation, StickyAssignor, manual offset commit)
    ▼
KafkaLogConsumer  ──── per batch ────►  AnomalyDetector
    │                                         │ alert fired?
    │  bulkWrite()                            ▼
    ▼                                   AlertEvent → in-memory list
Elasticsearch: logs-YYYY.MM.dd               (REST API serves via GET /alerts)
    │
    │  (S3ArchivalWorker, daily at 00:00 UTC)
    ▼
S3: logs/YYYY/MM/DD/logs-YYYY.MM.dd.ndjson
    │  (Glacier lifecycle after 90d, expiry after 7y)
    ▼
AWS Glacier
```

## Storage Sizing (rough estimates)

| Tier        | Backend       | Retention | Data shape                              |
|-------------|---------------|-----------|-----------------------------------------|
| Hot         | Elasticsearch | 7 days    | Raw JSON log docs, ~500 B/doc           |
| Warm        | S3 Standard   | 90 days   | NDJSON blobs, ~same size pre-compress   |
| Cold        | S3 Glacier    | 7 years   | Archived NDJSON, very low access cost   |
| Metrics     | InfluxDB      | unbounded | ~100 B/point, 1 point/s per service     |

## Key Design Decisions

### Exactly-Once Semantics
EOS in Kafka requires coordination at both ends:
- **Producer side**: `transactional.id` set to pod hostname, `enable.idempotence=true`
- **Consumer side**: `isolation.level=read_committed`, `enable.auto.commit=false`, manual `commitSync` only after all downstream writes succeed

This achieves at-least-once delivery with duplicate suppression — true EOS requires idempotent downstream writes (Elasticsearch `PUT /index/id` is idempotent by document ID).

### Virtual Threads vs. WebFlux
See [why-kafka-not-rabbitmq.md](why-kafka-not-rabbitmq.md) for the full decision record.

### Sticky Assignor
`StickyAssignor` minimises partition movement during rebalances compared to the default `RangeAssignor`. This matters for EOS because every rebalance creates a replay window where in-flight batches may be reprocessed. Fewer moved partitions = shorter replay window = lower effective duplicate rate.
