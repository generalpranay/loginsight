# LogInsight — Observability Platform

A production-style observability platform built with Java 21 virtual threads, Apache Kafka (Exactly-Once Semantics), sliding-window anomaly detection, and a real-time web dashboard. Logs flow from a built-in simulator (or your own services) through Kafka into Elasticsearch and InfluxDB, where they surface as searchable logs, anomaly alerts, and live service metrics — all visible in a browser without touching the terminal.

---

## What it looks like

Open **http://localhost:8081** after starting the API:

| Tab | What you get |
|-----|-------------|
| **Dashboard** | Live metric cards per service — msg/s, error rate, anomaly count. Cards turn red and pulse during error spikes. |
| **Logs** | Filterable log table (service, level, status code, time range). Logs appear within seconds of the simulator generating them. **＋ New Log** lets you manually submit entries from the browser. |
| **Alerts** | Anomaly alert cards with severity, spike %, and rate comparison. Updated in real time. |
| **Simulation** | Start / stop the log simulator from the browser. Shows live message count, spike indicator, and a countdown to the next spike. |

Auto-refresh runs every 5 seconds. Everything works even without a full infrastructure stack — see [Running without Docker](#running-without-docker).

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Browser  http://localhost:8081                             │
│  ┌──────────┬─────────┬──────────┬────────────┐            │
│  │Dashboard │  Logs   │ Alerts   │ Simulation │            │
│  └──────────┴─────────┴──────────┴────────────┘            │
└─────────────────────┬───────────────────────────────────────┘
                      │ REST (same origin)
                      ▼
          ┌───────────────────────┐
          │  Spring Boot API :8081│
          │                       │
          │  LogBuffer ◄──────────┼──── Kafka: raw-logs  ◄──────┐
          │  (in-memory, 50K)     │     (live capture)           │
          │                       │                              │
          │  AlertSubscriber ◄────┼──── Kafka: anomaly-alerts    │
          │  (in-memory, 10K)     │                              │
          │                       │                              │
          │  LogQueryService ─────┼──► Elasticsearch (if up)     │
          │  ElasticsearchWriter  │                              │
          │  InfluxDbWriter ──────┼──► InfluxDB (if up)          │
          └───────────────────────┘                              │
                                                                 │
          ┌─────────────────────────────────┐                   │
          │  Ingestion Worker (separate JVM)│                   │
          │                                 │                   │
          │  KafkaLogConsumer ──► AnomalyDetector ──► alerts ───┤
          │                   └─► MetricsAggregator ──► InfluxDB│
          │                   └─► ElasticsearchWriter ──► ES    │
          └─────────────────────────────────┘                   │
                                                                 │
          ┌──────────────────────────────┐                      │
          │  LogSimulator                │                      │
          │  (built-in or standalone)    │──► Kafka: raw-logs ──┘
          └──────────────────────────────┘

          S3ArchivalWorker (in ingestion)
               └──► ES indices > 7 days → S3 NDJSON → delete index

          OpenTelemetry → Prometheus :9090 → Grafana :3000
```

**Key insight:** The API's `LogBuffer` subscribes directly to the `raw-logs` Kafka topic (the same one the simulator publishes to). This means simulator logs appear in the Logs tab immediately — no Elasticsearch required.

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Concurrency | Java 21 virtual threads (Project Loom) |
| Message broker | Apache Kafka 3.6 with Exactly-Once Semantics |
| Hot log storage | Elasticsearch 8.11 (day-partitioned indices) |
| Metrics storage | InfluxDB 2.7 |
| Cold archival | AWS S3 + Glacier lifecycle |
| Instrumentation | OpenTelemetry Java SDK 1.33 → Prometheus |
| REST API + UI | Spring Boot 3.2 (virtual threads + static dashboard) |
| Infrastructure | Terraform (AWS MSK + S3), Helm (Kubernetes) |

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java JDK | 21+ |
| Apache Maven | 3.9+ |
| Docker Desktop | Latest (optional — see below) |

```bash
java -version        # must print 21 or higher
mvn -version         # must print 3.9.x
docker compose version
```

---

## Quick start

### Option A — API only (no Docker needed)

The API degrades gracefully when backends are not available. Simulator logs still appear in the Logs tab via the in-memory buffer, and manual log entries work immediately.

```powershell
# 1. Build
mvn clean package -DskipTests

# 2. Start the API
.\run-api.ps1
```

Open **http://localhost:8081**, go to the **Simulation** tab, and click **▶ Start Simulation**. Logs appear in the Logs tab within a few seconds. The first error spike fires after ~20 seconds.

### Option B — Full stack with Docker

Adds persistent Elasticsearch storage, InfluxDB metrics, Grafana dashboards, and Kibana.

```powershell
# 1. Start infrastructure
docker compose up -d

# 2. Wait for all containers to become healthy (~60 s)
docker compose ps

# 3. One-time: create the Elasticsearch index template
#    (see "Elasticsearch setup" section below)

# 4. Build all modules
mvn clean package -DskipTests

# 5. Start the ingestion worker (reads Kafka → writes ES + InfluxDB + anomaly detection)
.\run-ingestion.ps1

# 6. Start the REST API + dashboard
.\run-api.ps1

# 7. Open the dashboard
start http://localhost:8081
```

Go to the **Simulation** tab and click **▶ Start Simulation**, or run the simulator as a standalone process (see [Running the simulator standalone](#running-the-simulator-standalone)).

---

## Running without Docker

When `LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS` is not set (or Kafka is unreachable), the API starts cleanly with these behaviours:

| Feature | Without Docker | With Docker + Kafka |
|---------|---------------|---------------------|
| Manual log entry (＋ New Log) | ✓ Works — stored in memory | ✓ Works |
| View manually submitted logs | ✓ Works | ✓ Works |
| Simulator start/stop | ✗ Disabled (Kafka required) | ✓ Works |
| Simulator logs in Logs tab | ✗ N/A | ✓ Via LogBuffer |
| Persistent logs (survive restart) | ✗ Memory only | ✓ Elasticsearch |
| Metrics (Dashboard cards) | ✗ "No data" | ✓ Via InfluxDB |
| Anomaly alerts | ✗ None | ✓ After ~2 min |

The `run-api.ps1` script sets `LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS=localhost:9092` automatically — Kafka just needs to be reachable at that address.

---

## Web dashboard

### Starting / stopping the simulator

The **Simulation** tab in the dashboard is the easiest way to control the simulator:

- **▶ Start Simulation** — starts the log generator inside the API process. No terminal needed.
- **■ Stop Simulation** — stops it cleanly.
- **Next spike in** — countdown to the next error spike on `payment-service`.

The simulator produces ~40 msg/s across 5 services. After the first 20 seconds it injects a 15-second error spike (80 msg/s, 70 % HTTP 500s on `payment-service`), which triggers the anomaly detector if the ingestion worker is running.

### Submitting logs manually

In the **Logs** tab, click **＋ New Log**:

- Choose a service, level (INFO / WARN / ERROR), status code, host, and message.
- The entry is stored immediately in the in-memory buffer and appears in the table on submit.
- Works without Kafka or Elasticsearch.

### Logs tab filters

| Filter | Default |
|--------|---------|
| Service | All |
| Level | All |
| From | 24 hours ago |
| To | Now |
| Limit | 100 |

Results come from Elasticsearch (when configured) **merged** with the in-memory buffer, so simulator logs are always visible even without ES.

---

## API reference

All endpoints are served on port **8081**.

### Logs

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/logs` | Search logs. Params: `service`, `from` (ISO-8601), `to` (ISO-8601), `limit` (1–1000, default 100). Results merge Elasticsearch + in-memory buffer, newest first. |
| `POST` | `/api/v1/logs` | Submit a log entry manually. Body: `{ service, level, statusCode, message, host, tags }`. Returns the created entry with generated `id` and `timestamp`. |

### Alerts

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/alerts` | All cached anomaly alerts, newest first. Optional param: `service`. |

### Metrics

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/metrics/summary` | Latest throughput/error-rate snapshot from InfluxDB. Required param: `service`. |

### Simulation

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/simulation/start` | Start the log simulator (requires Kafka). |
| `POST` | `/api/v1/simulation/stop` | Stop the simulator. |
| `GET` | `/api/v1/simulation/status` | Returns `{ running, totalSent, spikeActive, nextSpikeInSeconds, bootstrapServers, topic }`. |

### Utility

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/services` | List of simulated service names. |
| `GET` | `/api/v1/health` | Liveness probe. |
| `GET` | `/actuator/health` | Spring Boot health (detailed when authorized). |
| `GET` | `/actuator/prometheus` | Prometheus metrics scrape endpoint. |

---

## Elasticsearch setup (one-time)

Run this after `docker compose up -d`, before starting the ingestion worker. Elasticsearch stores the template internally — you never need to repeat it.

**PowerShell:**
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

**bash:**
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

Expected response: `{ "acknowledged": true }`.

---

## Running the simulator standalone

The simulator can also run as a separate process — useful when running the ingestion worker on a different machine or when you want more control over traffic volume.

**PowerShell:**
```powershell
.\run-simulator.ps1
```

Or manually:
```powershell
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:KAFKA_TOPIC             = 'raw-logs'
java -cp ingestion\target\ingestion-1.0.0-SNAPSHOT.jar com.loginsight.ingestion.LogSimulator
```

Output every 10 seconds:
```
Simulator stats: total_sent=412 spike_active=false
```

After ~20 seconds (first run), then every 90 seconds:
```
SPIKE START on 'payment-service' — sending 80 msg/s for 15s
SPIKE END on 'payment-service'
```

---

## Infrastructure URLs

| Service | URL | Default credentials |
|---------|-----|---------------------|
| LogInsight Dashboard | http://localhost:8081 | — |
| Kafka | `localhost:9092` | — |
| Elasticsearch | http://localhost:9200 | no auth |
| Kibana | http://localhost:5601 | no auth |
| InfluxDB | http://localhost:8086 | `admin` / `adminpassword` |
| Prometheus | http://localhost:9090 | no auth |
| Grafana | http://localhost:3000 | `admin` / `admin` |

### Grafana
Auto-provisioned `loginsight-overview` dashboard. InfluxDB and Prometheus datasources are pre-configured.

### Kibana
**Discover → Create index pattern** → `logs-*` → time field: `timestamp`.

---

## Module structure

```
loginsight/
├── common/          LogEntry, AlertEvent, MetricSnapshot records
├── telemetry/       OpenTelemetry SDK bootstrap, Prometheus exporter
├── anomaly/         AnomalyDetector — 5-min sliding window, 6× spike threshold
├── storage/         ElasticsearchWriter, InfluxDbWriter, MetricsAggregator, S3ArchivalWorker
├── ingestion/       KafkaLogConsumer (EOS), AlertPublisher, LogSimulator
├── api/             Spring Boot REST API + web dashboard
│   ├── LogIngestionController   GET + POST /api/v1/logs, GET /api/v1/alerts, /metrics/summary
│   ├── SimulationController     POST /api/v1/simulation/start|stop, GET /status
│   ├── AlertSubscriber          Kafka consumer for anomaly-alerts → in-memory cache
│   ├── LogBuffer                Kafka consumer for raw-logs → in-memory cache (50K entries)
│   ├── SimulationService        Manages LogSimulator lifecycle
│   ├── LogQueryService          Merges Elasticsearch + LogBuffer results
│   └── resources/static/        index.html — single-page dashboard
├── infrastructure/  Prometheus config, Grafana dashboards + datasource provisioning
├── helm/loginsight/ Kubernetes Deployment, HPA, ConfigMap
├── terraform/       AWS MSK cluster + S3 bucket
└── docs/            Architecture diagrams, decision records
```

---

## Key design decisions

**EOS consumer** — `enable.auto.commit=false`, `isolation.level=read_committed`, `commitSync` only after `bulkWrite()` succeeds. Document ID = producer-assigned UUID → re-indexing is idempotent on redelivery.

**Anomaly detection** — 5 × 1-minute buckets per `(service, statusCode)` pair. Alert fires when `currentMinute >= avg(prior4) × 6.0`. Minimum 5 baseline events suppresses cold-start false positives.

**Virtual threads everywhere** — `Thread.ofVirtual()` in every Kafka poll loop; `spring.threads.virtual.enabled=true` in the API. No reactive types, no thread pool tuning.

**LogBuffer** — The API subscribes to the raw-logs Kafka topic itself (with `auto.offset.reset=latest`), mirroring the `AlertSubscriber` pattern for alerts. This lets the dashboard display simulator output in real time without requiring Elasticsearch to be running.

**Graceful degradation** — Every backend (ES, InfluxDB, Kafka) is optional. Connection URLs default to `localhost` equivalents; missing URLs log a warning and the component becomes a no-op. The API always starts.

**S3 archival safety** — Search-after pagination avoids the 10,000-doc ES limit. The index is deleted only after S3 confirms the upload — no data loss on partial failure.

---

## Shutdown

```powershell
# Stop Java processes: Ctrl+C in each terminal, then:

# Keep Docker volumes (data survives)
docker compose down

# Wipe everything including stored data
docker compose down -v
```

---

## Troubleshooting

### Logs tab shows "No logs found" after starting the simulator

The simulator publishes to Kafka. The `LogBuffer` in the API captures from Kafka and feeds the Logs tab — but only messages produced **after the API started**. If the Logs tab is empty:

1. Confirm the simulator is running (Simulation tab shows **● Running**).
2. Wait a few seconds — the buffer subscribes with `auto.offset.reset=latest`.
3. Check that `LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS` resolves to a reachable Kafka broker (shown in the Simulation → Configuration card).
4. Widen the time range filter in the Logs tab (default is last 24 hours).

### No anomaly alerts after the spike

Alerts require the **ingestion worker** to be running (it runs the anomaly detector). The API-embedded simulator only publishes to Kafka; it does not run anomaly detection itself. Start `.\run-ingestion.ps1` in a second terminal.

### Simulator Start button is disabled

The button is disabled when `bootstrapServers` is empty. Use `.\run-api.ps1` (which sets `LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS=localhost:9092`) or set the environment variable manually.

### InfluxDB login fails / no metrics on dashboard

The InfluxDB init credentials only apply on the first container boot. If you started InfluxDB before the env vars were set:
```powershell
docker compose down -v   # wipes volumes
docker compose up -d
```

### No data in Elasticsearch after simulator starts

1. Confirm the ingestion worker is running and shows `KafkaLogConsumer started`.
2. List Kafka topics: `docker exec loginsight-kafka kafka-topics --list --bootstrap-server localhost:9092`
3. Check the ES template: `Invoke-RestMethod http://localhost:9200/_index_template/logs-template`
4. Check ES cluster health: `Invoke-RestMethod http://localhost:9200/_cluster/health`

### API jar is tiny (12 KB) / fails to start

The Spring Boot fat JAR requires the `repackage` Maven goal. Rebuild:
```powershell
mvn clean package -pl api -am -DskipTests
```

Or skip the JAR entirely and use `.\run-api.ps1` which runs via `mvn spring-boot:run`.

### Zookeeper stays unhealthy

The `ruok` four-letter command is disabled in newer Confluent images. The `docker-compose.yml` in this repo already uses `srvr` — if you cloned an older version, update the Zookeeper healthcheck:
```yaml
healthcheck:
  test: ["CMD", "bash", "-c", "echo srvr | nc localhost 2181 | grep -q Mode"]
```

### Port conflicts

Edit `docker-compose.yml` and change the left side of the port mapping — e.g. `"19200:9200"` for Elasticsearch on port 19200.
