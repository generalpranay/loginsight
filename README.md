# Log-to-Insight Observability Platform

A senior-level portfolio project: a simplified ELK-style observability platform that ingests high-volume log streams, detects anomalies in near-real-time, and stores logs in hot (Elasticsearch) + cold (S3) tiers — all built with Java 21 virtual threads, Apache Kafka EOS, and OpenTelemetry.

## Architecture overview

```
Log Producers  →  Kafka (EOS)  →  Ingestion Workers (3 replicas, virtual threads)
                                        │
                    ┌───────────────────┼────────────────────┐
                    ▼                   ▼                    ▼
             Elasticsearch       AnomalyDetector         InfluxDB
             (raw logs,          (sliding window,        (metrics:
              7-day hot tier)     500% spike alert)       mps, error_rate)
                    │
                    ▼ (daily, S3ArchivalWorker)
                   S3  →  Glacier (90d+)

REST API (Spring Boot 3.2, virtual threads)
    GET /api/v1/logs
    GET /api/v1/alerts
    GET /api/v1/metrics/summary

Metrics: OpenTelemetry → Prometheus → Grafana
```

See [`docs/architecture.md`](docs/architecture.md) for the full C4 diagram.

## Tech stack

| Layer            | Technology                                      |
|------------------|-------------------------------------------------|
| Concurrency      | Java 21 virtual threads (Project Loom)          |
| Message broker   | Apache Kafka 3.6 with EOS                       |
| Hot log storage  | Elasticsearch 8.11 (day-partitioned indices)    |
| Metrics storage  | InfluxDB 2.7                                    |
| Cold archival    | AWS S3 + Glacier lifecycle                      |
| Instrumentation  | OpenTelemetry Java SDK 1.33 → Prometheus        |
| REST API         | Spring Boot 3.2 (Tomcat + virtual threads)      |
| Infrastructure   | Terraform (MSK + S3), Helm (K8s deployment)     |

## Local setup

### Prerequisites

- Docker Desktop
- Java 21+
- Maven 3.9+

### Start infrastructure

```bash
cd /path/to/loginsight
docker compose up -d
```

Wait for all services to be healthy (~60 s). Check with:

```bash
docker compose ps
```

Services exposed:

| Service       | URL                         |
|---------------|-----------------------------|
| Kafka         | localhost:9092              |
| Elasticsearch | http://localhost:9200       |
| Kibana        | http://localhost:5601       |
| InfluxDB      | http://localhost:8086       |
| Prometheus    | http://localhost:9090       |
| Grafana       | http://localhost:3000       |

Grafana default credentials: `admin / admin`

### Build all modules

```bash
mvn clean package -DskipTests
```

### Run the ingestion pipeline

Set required environment variables, then start the ingestion worker:

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_TOPIC=raw-logs
export KAFKA_GROUP_ID=loginsight-ingestion
export ELASTICSEARCH_URL=http://localhost:9200
export INFLUXDB_URL=http://localhost:8086
export INFLUXDB_TOKEN=loginsight-influxdb-token
export INFLUXDB_ORG=loginsight
export INFLUXDB_BUCKET=metrics
export S3_BUCKET=my-cold-logs-bucket      # Only needed for archival worker
export AWS_REGION=us-east-1

java -jar ingestion/target/ingestion-1.0.0-SNAPSHOT.jar
```

### Run the REST API

```bash
export ELASTICSEARCH_URL=http://localhost:9200
export INFLUXDB_URL=http://localhost:8086
export INFLUXDB_TOKEN=loginsight-influxdb-token
export INFLUXDB_ORG=loginsight
export INFLUXDB_BUCKET=metrics

java -jar api/target/api-1.0.0-SNAPSHOT.jar
```

### Produce test log events

```bash
# Produce 1 000 sample log events (mix of 200, 404, 500 codes)
docker exec -i loginsight-kafka kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic raw-logs << 'EOF'
{"id":"a1","service":"checkout","level":"ERROR","statusCode":500,"message":"DB timeout","host":"host-1","traceId":"abc","timestamp":"2024-01-15T10:30:00Z","tags":{}}
{"id":"a2","service":"checkout","level":"INFO","statusCode":200,"message":"OK","host":"host-1","traceId":"def","timestamp":"2024-01-15T10:30:01Z","tags":{}}
EOF
```

### Query the API

```bash
# Get recent logs for the checkout service
curl "http://localhost:8080/api/v1/logs?service=checkout&limit=50"

# Get anomaly alerts
curl "http://localhost:8080/api/v1/alerts"

# Get metrics summary
curl "http://localhost:8080/api/v1/metrics/summary?service=checkout"

# Health check
curl "http://localhost:8080/api/v1/health"
```

## Module structure

```
loginsight/
├── common/          Records: LogEntry, AlertEvent, MetricSnapshot
├── telemetry/       OpenTelemetry SDK bootstrap, custom metrics
├── ingestion/       KafkaLogConsumer (EOS, StickyAssignor, virtual threads)
├── anomaly/         AnomalyDetector (5-min sliding window, 500% threshold)
├── storage/         ElasticsearchWriter, InfluxDbWriter, S3ArchivalWorker
├── api/             Spring Boot REST API
├── terraform/       MSK cluster + S3 bucket on AWS
├── helm/loginsight/ K8s Deployment + HPA + ConfigMap
├── infrastructure/  Prometheus + Grafana config
└── docs/            Architecture, decision records, chaos report
```

## Key implementation details

- **EOS consumer**: `enable.auto.commit=false`, `isolation.level=read_committed`, manual `commitSync` only after `bulkWrite()` returns. Revocation listener commits before partition hand-off.
- **Anomaly detection**: 5 one-minute buckets per `(service, statusCode)`. Alert fires when `currentBucket >= avg(prior4) * 6.0`. Minimum 5 baseline events required to suppress cold-start false positives.
- **S3 archival**: search-after pagination (no 10 000 doc limit), delete-only-after-upload atomicity, 24-hour virtual-thread schedule.
- **Virtual threads**: `Thread.ofVirtual().start(pollLoop)` in the consumer; `spring.threads.virtual.enabled=true` in the API. No reactive types anywhere.

## Decision records

- [Why Kafka, not RabbitMQ; why virtual threads, not WebFlux](docs/why-kafka-not-rabbitmq.md)
- [Chaos engineering: ES node kill + Kafka broker kill](docs/chaos-engineering-report.md)
