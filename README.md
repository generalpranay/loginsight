# Log-to-Insight Observability Platform

A production-style ELK-style observability platform built with Java 21 virtual threads, Apache Kafka (Exactly-Once Semantics), sliding-window anomaly detection, and dual-write to Elasticsearch (hot tier) and InfluxDB (metrics). Includes S3 cold archival, a Spring Boot REST API, OpenTelemetry instrumentation, and a built-in log simulator.

## Architecture

```
LogSimulator (fake traffic)
       │  JSON → Kafka topic: raw-logs (6 partitions)
       ▼
  Apache Kafka
       │
       ▼
 KafkaLogConsumer  (virtual thread, EOS, manual offset commit)
       │
       ├──► AnomalyDetector  ──► AlertPublisher ──► Kafka: anomaly-alerts
       │    (5-min sliding window,                   (3 partitions)
       │     fires at 6× baseline spike)
       │
       ├──► MetricsAggregator ──► InfluxDB
       │    (per-service counters,    (bucket: metrics)
       │     flush every 10s)
       │
       └──► ElasticsearchWriter
            (bulk index → logs-YYYY.MM.dd)

 S3ArchivalWorker  (every 24h)
       └──► reads indices older than 7 days → upload NDJSON to S3 → delete index

 Spring Boot API  :8081
       ├── GET /api/v1/logs?service=&from=&to=&limit=
       ├── GET /api/v1/alerts
       └── GET /api/v1/metrics/summary

 OpenTelemetry → Prometheus :9090 → Grafana :3000
```

## Tech stack

| Layer            | Technology                                     |
|------------------|------------------------------------------------|
| Concurrency      | Java 21 virtual threads (Project Loom)         |
| Message broker   | Apache Kafka 3.6 with EOS                      |
| Hot log storage  | Elasticsearch 8.11 (day-partitioned indices)   |
| Metrics storage  | InfluxDB 2.7                                   |
| Cold archival    | AWS S3 + Glacier lifecycle                     |
| Instrumentation  | OpenTelemetry Java SDK 1.33 → Prometheus       |
| REST API         | Spring Boot 3.2 (Tomcat + virtual threads)     |
| Infrastructure   | Terraform (MSK + S3), Helm (K8s deployment)    |

---

## Prerequisites

| Tool | Version | Download |
|------|---------|----------|
| Docker Desktop | Latest | https://www.docker.com/products/docker-desktop |
| Java JDK | 21+ | https://adoptium.net |
| Apache Maven | 3.9+ | https://maven.apache.org/download.cgi |

Verify your setup:

```bash
java -version    # should print 21 or higher
mvn -version     # should print 3.9.x
docker compose version
```

---

## Quick Start (5 steps)

```
1. docker compose up -d          # start all infrastructure
2. create ES index template      # one-time REST call (see below)
3. mvn clean package -DskipTests # build all JARs
4. start ingestion worker        # reads Kafka → writes ES + InfluxDB
5. start log simulator           # generates fake traffic
```

---

## Step 1 — Start infrastructure

```bash
cd /path/to/loginsight
docker compose up -d
```

**Windows (PowerShell):**
```powershell
cd C:\path\to\loginsight
docker compose up -d
```

Wait until all containers are healthy (~60 seconds):

```bash
docker compose ps
```

Expected output — all containers should show `(healthy)`:

```
loginsight-zookeeper       Up (healthy)
loginsight-kafka           Up (healthy)
loginsight-elasticsearch   Up (healthy)
loginsight-influxdb        Up (healthy)
loginsight-kibana          Up (healthy)
loginsight-prometheus      Up (healthy)
loginsight-grafana         Up (healthy)
```

### Service URLs and credentials

| Service       | URL                       | Credentials              |
|---------------|---------------------------|--------------------------|
| Kafka         | `localhost:9092`          | —                        |
| Elasticsearch | http://localhost:9200     | no auth                  |
| Kibana        | http://localhost:5601     | no auth                  |
| InfluxDB      | http://localhost:8086     | `admin` / `adminpassword`|
| Prometheus    | http://localhost:9090     | no auth                  |
| Grafana       | http://localhost:3000     | `admin` / `admin`        |

> **InfluxDB first-run**: the `DOCKER_INFLUXDB_INIT_*` env vars only apply on the very first boot.
> If you previously started InfluxDB without them, run `docker compose down -v` to wipe volumes and restart.

---

## Step 2 — Create Elasticsearch index template

This is a **one-time setup** that tells Elasticsearch the field types for every `logs-*` index.
Run it once after the container is healthy. Elasticsearch stores it internally — you never need to repeat this.

**Mac / Linux:**
```bash
curl -X PUT "http://localhost:9200/_index_template/logs-template" \
  -H "Content-Type: application/json" \
  -d '{
    "index_patterns": ["logs-*"],
    "template": {
      "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
      "mappings": {
        "properties": {
          "id":         { "type": "keyword" },
          "service":    { "type": "keyword" },
          "level":      { "type": "keyword" },
          "statusCode": { "type": "integer" },
          "message":    { "type": "text" },
          "host":       { "type": "keyword" },
          "traceId":    { "type": "keyword" },
          "timestamp":  { "type": "date" },
          "tags":       { "type": "object", "dynamic": true }
        }
      }
    }
  }'
```

**Windows (PowerShell):**
```powershell
$body = @'
{
  "index_patterns": ["logs-*"],
  "template": {
    "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
    "mappings": {
      "properties": {
        "id":         { "type": "keyword" },
        "service":    { "type": "keyword" },
        "level":      { "type": "keyword" },
        "statusCode": { "type": "integer" },
        "message":    { "type": "text" },
        "host":       { "type": "keyword" },
        "traceId":    { "type": "keyword" },
        "timestamp":  { "type": "date" },
        "tags":       { "type": "object", "dynamic": true }
      }
    }
  }
}
'@
Invoke-RestMethod -Method PUT -Uri "http://localhost:9200/_index_template/logs-template" `
  -ContentType "application/json" -Body $body
```

You should get `acknowledged: true`. Verify:

```bash
# Mac/Linux
curl http://localhost:9200/_index_template/logs-template

# Windows PowerShell
Invoke-RestMethod -Uri "http://localhost:9200/_index_template/logs-template"
```

---

## Step 3 — Build all modules

```bash
mvn clean package -DskipTests
```

This builds 6 modules in order: `common → telemetry → anomaly → storage → ingestion → api`.

- `ingestion/target/ingestion-1.0.0-SNAPSHOT.jar` — fat JAR (~48 MB, shade plugin)
- `api/target/api-1.0.0-SNAPSHOT.jar` — fat JAR (Spring Boot repackage)

---

## Step 4 — Run the ingestion worker

The ingestion worker consumes `raw-logs` from Kafka and fans out to Elasticsearch, InfluxDB, and the anomaly detector.

**Mac / Linux:**
```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_TOPIC=raw-logs
export KAFKA_GROUP_ID=loginsight-ingestion
export ELASTICSEARCH_URL=http://localhost:9200
export INFLUXDB_URL=http://localhost:8086
export INFLUXDB_TOKEN=loginsight-influxdb-token
export INFLUXDB_ORG=loginsight
export INFLUXDB_BUCKET=metrics

java -jar ingestion/target/ingestion-1.0.0-SNAPSHOT.jar
```

**Windows (PowerShell):**
```powershell
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:KAFKA_TOPIC             = 'raw-logs'
$env:KAFKA_GROUP_ID          = 'loginsight-ingestion'
$env:ELASTICSEARCH_URL       = 'http://localhost:9200'
$env:INFLUXDB_URL            = 'http://localhost:8086'
$env:INFLUXDB_TOKEN          = 'loginsight-influxdb-token'
$env:INFLUXDB_ORG            = 'loginsight'
$env:INFLUXDB_BUCKET         = 'metrics'

java -jar ingestion\target\ingestion-1.0.0-SNAPSHOT.jar
```

> You can also use the included launch script: `.\run-ingestion.ps1`

You should see:
```
KafkaLogConsumer started on virtual thread, topic='raw-logs'
MetricsAggregator started — flush interval 10s
```

---

## Step 5 — Run the REST API

The API exposes log query, alert, and metrics endpoints on port **8081**.

**Mac / Linux:**
```bash
export ELASTICSEARCH_URL=http://localhost:9200
export INFLUXDB_URL=http://localhost:8086
export INFLUXDB_TOKEN=loginsight-influxdb-token
export INFLUXDB_ORG=loginsight
export INFLUXDB_BUCKET=metrics
export LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS=localhost:9092

java -jar api/target/api-1.0.0-SNAPSHOT.jar
```

**Windows (PowerShell):**
```powershell
$env:ELASTICSEARCH_URL                  = 'http://localhost:9200'
$env:INFLUXDB_URL                       = 'http://localhost:8086'
$env:INFLUXDB_TOKEN                     = 'loginsight-influxdb-token'
$env:INFLUXDB_ORG                       = 'loginsight'
$env:INFLUXDB_BUCKET                    = 'metrics'
$env:LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'

java -jar api\target\api-1.0.0-SNAPSHOT.jar
```

**Shortcut for local dev** — uses `application-local.properties` (all values pre-filled):
```bash
# Mac/Linux
mvn spring-boot:run -pl api -Dspring-boot.run.profiles=local

# Windows PowerShell
mvn spring-boot:run -pl api "-Dspring-boot.run.profiles=local"
```

> You can also use the included launch script: `.\run-api.ps1`

API is ready when you see:
```
Started LogInsightApplication in X.XXX seconds
```

---

## Step 6 — Run the log simulator (optional but recommended)

The simulator generates realistic traffic for 5 services (auth, checkout, payment, search, user)
at ~40 msg/s, plus an error spike on `payment-service` every 90 seconds that triggers the anomaly detector.

**Mac / Linux:**
```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_TOPIC=raw-logs

java -cp ingestion/target/ingestion-1.0.0-SNAPSHOT.jar \
  com.loginsight.ingestion.LogSimulator
```

**Windows (PowerShell):**
```powershell
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:KAFKA_TOPIC             = 'raw-logs'

java -cp ingestion\target\ingestion-1.0.0-SNAPSHOT.jar `
  com.loginsight.ingestion.LogSimulator
```

> You can also use the included launch script: `.\run-simulator.ps1`

You should see stats every 10 seconds:
```
Simulator stats: total_sent=412 spike_active=false
```

After ~90 seconds, a spike fires:
```
SPIKE START on 'payment-service' — sending 80 msg/s for 15s
```

---

## Step 7 — Verify data is flowing

### Check Elasticsearch has today's index

```bash
# Mac/Linux
curl http://localhost:9200/_cat/indices?v

# Windows PowerShell
Invoke-RestMethod -Uri "http://localhost:9200/_cat/indices?v"
```

You should see `logs-YYYY.MM.DD` with a growing `docs.count`.

### Query the API

```bash
# Mac/Linux — recent logs for payment-service
curl "http://localhost:8081/api/v1/logs?service=payment-service&limit=10"

# Anomaly alerts (fires after first spike, ~90s after simulator starts)
curl "http://localhost:8081/api/v1/alerts"

# Metrics summary
curl "http://localhost:8081/api/v1/metrics/summary"

# Health check
curl "http://localhost:8081/actuator/health"
```

```powershell
# Windows PowerShell
Invoke-RestMethod "http://localhost:8081/api/v1/logs?service=payment-service&limit=10"
Invoke-RestMethod "http://localhost:8081/api/v1/alerts"
Invoke-RestMethod "http://localhost:8081/actuator/health"
```

### Open Grafana

Go to http://localhost:3000 → login `admin / admin`.
InfluxDB and Prometheus datasources are pre-configured. The `loginsight-overview` dashboard is auto-provisioned.

### Open Kibana

Go to http://localhost:5601 → **Discover** → create index pattern `logs-*` with `timestamp` as the time field.

---

## Shutdown

Stop just the Java processes with `Ctrl+C` in each terminal, then:

```bash
# Keep volumes (data survives restart)
docker compose down

# Wipe everything including stored data
docker compose down -v
```

---

## Module structure

```
loginsight/
├── common/          Records: LogEntry, AlertEvent, MetricSnapshot
├── telemetry/       OpenTelemetry SDK bootstrap, Prometheus exporter
├── anomaly/         AnomalyDetector — 5-min sliding window, 6× spike threshold
├── storage/         ElasticsearchWriter, InfluxDbWriter, MetricsAggregator, S3ArchivalWorker
├── ingestion/       KafkaLogConsumer (EOS), AlertPublisher, LogSimulator
├── api/             Spring Boot REST API — LogIngestionController, AlertSubscriber
├── infrastructure/  Prometheus config, Grafana dashboards + datasource provisioning
├── helm/loginsight/ Kubernetes Deployment, HPA, ConfigMap
├── terraform/       AWS MSK cluster + S3 bucket
└── docs/            Architecture diagrams, decision records
```

---

## Key design decisions

- **EOS consumer**: `enable.auto.commit=false`, `isolation.level=read_committed`, `commitSync` only after `bulkWrite()` returns. Document ID = producer-assigned UUID → idempotent re-indexing on redelivery.
- **Anomaly detection**: 5 × 1-minute buckets per `(service, statusCode)` pair. Alert fires when `currentMinute >= avg(prior4) × 6.0`. Minimum 5 baseline events to suppress cold-start false positives.
- **Virtual threads everywhere**: `Thread.ofVirtual()` in the Kafka poll loop; `spring.threads.virtual.enabled=true` in the API. No reactive types, no thread pool tuning.
- **S3 archival safety**: search-after pagination (no 10,000-doc limit), index deleted only after S3 confirms upload — prevents data loss on partial failure.

---

## Troubleshooting

### Zookeeper stays unhealthy
The `ruok` four-letter command is disabled by default in newer Confluent images.
The `docker-compose.yml` in this repo already uses `srvr` instead — if you cloned an older version, update the Zookeeper healthcheck:
```yaml
healthcheck:
  test: ["CMD", "bash", "-c", "echo srvr | nc localhost 2181 | grep -q Mode"]
```

### InfluxDB login fails
The init credentials only apply on first container boot. If you started InfluxDB before
setting the env vars, the volume has a blank DB. Fix:
```bash
docker compose down -v   # wipes volumes
docker compose up -d
```

### API jar is tiny / fails to start
The Spring Boot fat JAR requires an explicit `repackage` execution in `api/pom.xml`.
This repo includes it — if you see a 12 KB jar, rebuild:
```bash
mvn clean package -pl api -am -DskipTests
```
Or run without a fat jar using `mvn spring-boot:run -pl api -Dspring-boot.run.profiles=local`.

### No data in Elasticsearch after simulator starts
1. Check the ingestion worker is running and shows `KafkaLogConsumer started`.
2. Check Kafka topics exist: `docker exec loginsight-kafka kafka-topics --list --bootstrap-server localhost:9092`
3. Check ES template exists: `curl http://localhost:9200/_index_template/logs-template`
4. Confirm ES is healthy: `curl http://localhost:9200/_cluster/health`

### Port conflicts
If any port is already in use on your machine, edit `docker-compose.yml` and change the left side of the port mapping (e.g. `"19200:9200"` for ES).
