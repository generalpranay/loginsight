# LogInsight — Observability Platform

A production-style observability platform built with **Java 21 virtual threads**, **Apache Kafka (Exactly-Once Semantics)**, a **sliding-window anomaly detector**, and a **real-time web dashboard**. Logs flow from a built-in simulator (or your own services) through Kafka into Elasticsearch and InfluxDB, where they surface as searchable logs, anomaly alerts, and live service metrics — all visible in a browser without touching the terminal.

> **TL;DR** — `mvn clean package -DskipTests` → `.\run-api.ps1` → open `http://localhost:8081` → click **Start Simulation**. Logs appear in seconds; the first anomaly fires within ~20 s.

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
          │  InProcessPipeline ◄──┼──── Kafka: raw-logs          │
          │   ├─ AnomalyDetector  │     (feeds Dashboard + Alerts│
          │   └─ MetricsAggregator│      without needing the     │
          │                       │      ingestion worker)       │
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

**Two key insights about the API process:**

1. **`LogBuffer`** subscribes directly to `raw-logs` so simulator output appears in the Logs tab immediately — Elasticsearch is optional.
2. **`InProcessPipeline`** runs the AnomalyDetector and MetricsAggregator **inside the API process** off the same Kafka stream. This means the Dashboard cards and Alerts tab populate even when the standalone ingestion worker isn't running — and the ingestion worker continues to be the durable, scalable path that writes to ES and InfluxDB.

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

Open **http://localhost:8081**, go to the **Simulation** tab, and click **▶ Start Simulation**. Logs appear in the Logs tab within a few seconds. The first error spike — and the first **anomaly alert** — fires after ~20 seconds.

### Option B — Full stack with Docker

Adds persistent Elasticsearch storage, InfluxDB metrics, Grafana dashboards, and Kibana.

```powershell
# 1. Create local env files from the templates and fill in secrets
Copy-Item .env.example .env
Copy-Item .env.ps1.example .env.ps1

# 2. Start infrastructure
docker compose up -d

# 3. Wait for all containers to become healthy (~60 s)
docker compose ps

# 4. One-time: create the Elasticsearch index template
#    (see "Elasticsearch setup" section below)

# 5. Build all modules
mvn clean package -DskipTests

# 6. Start the ingestion worker (reads Kafka → writes ES + InfluxDB + anomaly detection)
.\run-ingestion.ps1

# 7. Start the REST API + dashboard
.\run-api.ps1

# 8. Open the dashboard
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
| Dashboard metric cards | ✗ "No data" | ✓ Via InProcessPipeline |
| Anomaly alerts | ✗ None | ✓ First fires after ~20 s |
| Persistent logs (survive restart) | ✗ Memory only | ✓ Elasticsearch |
| Long-term metrics | ✗ Memory only | ✓ Via InfluxDB |

The `run-api.ps1` script sets `LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS=localhost:9092` automatically — Kafka just needs to be reachable at that address.

---

## Web dashboard

### Starting / stopping the simulator

The **Simulation** tab in the dashboard is the easiest way to control the simulator:

- **▶ Start Simulation** — starts the log generator inside the API process. No terminal needed.
- **■ Stop Simulation** — stops it cleanly.
- **Next spike in** — countdown to the next error spike on `payment-service`.

The simulator produces ~40 msg/s across 5 services. After the first 20 seconds it injects a 15-second error spike (80 msg/s, 70 % HTTP 500s on `payment-service`). The anomaly detector now fires on the **first** spike thanks to the cold-start floor — see [Anomaly detection](#anomaly-detection).

If `SIMULATION_API_KEY` is set on the server, the dashboard prompts for a key on first use (click **🔑 Set key** in the Simulation tab). The key is sent as `X-Simulation-Key` and compared in constant time.

### Submitting logs manually

In the **Logs** tab, click **＋ New Log**:

- Choose a service, level (INFO / WARN / ERROR), status code, host, and message.
- The entry is stored immediately in the in-memory buffer and appears in the table on submit.
- Works without Kafka or Elasticsearch.
- Input is validated: `host` ≤ 253 chars; `tags` ≤ 20 entries, key ≤ 64 chars, value ≤ 256 chars.

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

## Anomaly detection

The detector watches every `(service, statusCode)` pair through a **5-minute sliding window** of five 1-minute buckets. An alert fires when:

```
currentMinuteErrors  >=  avg(prior 4 minutes, excluding tainted)  ×  6.0
```

Two safety nets prevent the detector from silently swallowing real spikes:

| Safety net | What it does | Why |
|-----------|--------------|-----|
| **Cold-start floor** | If no usable baseline exists yet (first minutes after startup, or after a long quiet period), the detector fires when the current bucket alone exceeds **50 error events**. | Without this, the first spike at startup is suppressed by the "need ≥ 5 baseline events" check — exactly when the user is most likely to be watching. |
| **Tainted-bucket exclusion** | A bucket that itself fired an anomaly is flagged tainted and excluded from future baseline calculations until it ages out of the window. | A previous spike sitting in the rolling window would otherwise inflate the baseline (e.g., baseline=840, current=840 → ratio=1) and suppress the next spike. |

Suppression is **logged at INFO** (throttled to once per minute per `(service, statusCode)` so a 56 events/sec spike doesn't drown the console) so missed alerts are debuggable. Look for:

```
Suppressed (no baseline): service=payment-service statusCode=500 currentCount=42 coldStartFloor=50 ...
Suppressed (below 6× threshold): service=… current=… baseline=… need>=… excludedTaintedBuckets=…
```

Severity tiers: `WARNING` at 500–999 % over baseline, `CRITICAL` at ≥ 1 000 %.

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
| `GET` | `/api/v1/metrics/summary` | Latest throughput/error-rate snapshot. Required param: `service`. Reads from InfluxDB when configured, falls back to the in-memory `MetricsAggregator` snapshot. |

### Simulation

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/simulation/start` | Start the log simulator (requires Kafka). Requires `X-Simulation-Key` header if `SIMULATION_API_KEY` is set. |
| `POST` | `/api/v1/simulation/stop` | Stop the simulator. Same auth as `/start`. |
| `GET` | `/api/v1/simulation/status` | Returns `{ running, totalSent, spikeActive, nextSpikeInSeconds, bootstrapConfigured, topic }`. The Kafka broker URL is intentionally **not** exposed. |

### Utility

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/services` | List of simulated service names. |
| `GET` | `/api/v1/health` | Liveness probe. |
| `GET` | `/actuator/health` | Spring Boot health (detailed when authorized). |
| `GET` | `/actuator/prometheus` | Prometheus metrics scrape endpoint. |

### Response headers

All API responses carry these headers (set by a `FilterRegistrationBean` in `AppConfig`):

```
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 0
Referrer-Policy: strict-origin-when-cross-origin
Content-Security-Policy: default-src 'self'; …
```

CORS is **off by default**; set `CORS_ALLOWED_ORIGINS` to a comma-separated list to enable.

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

CLI flags:

| Flag | Default | Purpose |
|------|---------|---------|
| `--count N` | unlimited | Send exactly N messages then exit. |
| `--no-spike` | spikes on | Suppress the periodic error spike (baseline load tests). |

Output every 10 seconds:
```
Simulator stats: total_sent=412 spike_active=false
```

After ~20 seconds (first run), then every 90 seconds:
```
SPIKE START on 'payment-service' — sending 80 msg/s for 15s
SPIKE END on 'payment-service'
```

For higher-volume load tests, see `scripts/load-gen.sh` (Bash-friendly wrapper with CLI-arg validation).

---

## Infrastructure URLs

| Service | URL | Default credentials |
|---------|-----|---------------------|
| LogInsight Dashboard | http://localhost:8081 | — |
| Kafka | `localhost:9092` | — |
| Elasticsearch | http://localhost:9200 | no auth (dev only) |
| Kibana | http://localhost:5601 | no auth (dev only) |
| InfluxDB | http://localhost:8086 | from `.env` |
| Prometheus | http://localhost:9090 | no auth |
| Grafana | http://localhost:3000 | from `.env` |

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
├── anomaly/         AnomalyDetector — 5-min sliding window, 6× spike, cold-start floor, tainted buckets
├── storage/         ElasticsearchWriter, InfluxDbWriter, MetricsAggregator, S3ArchivalWorker
├── ingestion/       KafkaLogConsumer (EOS), AlertPublisher, LogSimulator
├── api/             Spring Boot REST API + web dashboard
│   ├── LogIngestionController   GET + POST /api/v1/logs, GET /api/v1/alerts, /metrics/summary
│   ├── SimulationController     POST /api/v1/simulation/start|stop, GET /status  (X-Simulation-Key)
│   ├── AlertSubscriber          Kafka consumer for anomaly-alerts → in-memory cache
│   ├── InProcessPipeline        Kafka consumer for raw-logs → AnomalyDetector + MetricsAggregator
│   ├── LogBuffer                Kafka consumer for raw-logs → in-memory cache (50K entries)
│   ├── SimulationService        Manages LogSimulator lifecycle
│   ├── LogQueryService          Merges Elasticsearch + LogBuffer results
│   └── resources/static/        index.html — single-page dashboard
├── infrastructure/  Prometheus config, Grafana dashboards + datasource provisioning
├── helm/loginsight/ Kubernetes Deployment, HPA, ConfigMap
├── terraform/       AWS MSK cluster + S3 bucket
├── scripts/         load-gen.sh (validated load generator), helper scripts
└── docs/            Architecture diagrams, decision records
```

---

## Key design decisions

**EOS consumer** — `enable.auto.commit=false`, `isolation.level=read_committed`, `commitSync` only after `bulkWrite()` succeeds. Document ID = producer-assigned UUID → re-indexing is idempotent on redelivery.

**Anomaly detection** — 5 × 1-minute buckets per `(service, statusCode)`. Alert fires when `currentMinute >= avg(prior 4, excluding tainted) × 6.0`. Cold-start floor (50 events) catches the first spike; tainted-bucket exclusion prevents one spike from poisoning the baseline of the next.

**Virtual threads everywhere** — `Thread.ofVirtual()` in every Kafka poll loop; `spring.threads.virtual.enabled=true` in the API. No reactive types, no thread pool tuning.

**LogBuffer + InProcessPipeline** — The API subscribes to `raw-logs` itself (own consumer group, `auto.offset.reset=latest`). LogBuffer feeds the Logs tab; InProcessPipeline drives the Dashboard metric cards and Alerts. This lets the dashboard be useful without the ingestion worker running, while the ingestion worker remains the durable, scalable path to ES and InfluxDB.

**Graceful degradation** — Every backend (ES, InfluxDB, Kafka) is optional. Connection URLs default to empty; missing URLs log a warning and the component becomes a no-op. The API always starts.

**S3 archival safety** — Search-after pagination avoids the 10,000-doc ES limit. The index is deleted only after S3 confirms the upload — no data loss on partial failure.

**Security defaults** — Secrets in env vars only (gitignored `.env` / `.env.ps1` files, with `.example` templates checked in). Simulation endpoints gated on `X-Simulation-Key` with constant-time comparison. CSP + standard hardening headers on every response. CORS off by default.

---

## Issues encountered & solutions

A development journal of bugs and pitfalls hit while building this project, and how each was resolved. Useful both as a debugging reference and as a record of how the architecture evolved.

### 1. Alerts always showed 0 even during an active SPIKE
**Symptom:** Dashboard metrics populated correctly, the simulator was clearly emitting 80 msg/s of HTTP 500s on `payment-service`, but the Alerts tab stayed empty. No `ANOMALY:` log line was emitted.

**Root cause — two separate bugs in `AnomalyDetector.evaluate()`:**

  - **Cold-start suppression.** The first spike fires at `t≈20s`, but the prior four 1-minute baseline buckets (representing time before the simulator started) are empty. The `hasBaseline = baselineTotal >= MIN_BASELINE_EVENTS` guard returned early, so the very first — and most visible — spike was always silently swallowed.
  - **Baseline poisoning.** Subsequent spikes have a baseline contaminated by the prior spike's ~840 events sitting in the window. `baselineRate ≈ 840`, `currentRate ≈ 840` → ratio ≈ 1.0, far below the 6× threshold. Every spike after the first was also suppressed.

**Fix (today):**
  - **Cold-start floor (50 events)** — when there is no usable baseline, fire on absolute current-bucket count instead.
  - **Tainted-bucket tracking** — each bucket that fires an anomaly is marked tainted and excluded from future baseline averages until it ages out of the 5-minute window. Implemented as a `boolean[] tainted` parallel to the count ring buffer, cleared in `advance()`.
  - **Throttled suppression logging** — INFO log line emitted whenever the detector suppresses an alert that *almost* fired (current bucket ≥ 20 events), rate-limited to once per minute per `(service, statusCode)` so a 56 evt/sec spike doesn't generate 56 log lines/sec. Makes the next "alerts still 0" debug session trivial.

### 2. Dashboard cards stuck at "No data" without the ingestion worker
**Symptom:** Running just `.\run-api.ps1` (no `.\run-ingestion.ps1`) left the Dashboard tab empty. Logs and Alerts tabs worked.

**Root cause:** Metrics and alerts were generated only by the standalone ingestion worker's `MetricsAggregator` + `AnomalyDetector`. Without it, nothing populated InfluxDB or the alert topic.

**Fix:** Added `InProcessPipeline` (api module) — the API now runs its own raw-logs Kafka consumer (own consumer group, so it doesn't compete with `LogBuffer` or the worker) and feeds each log entry through `AnomalyDetector` + `MetricsAggregator` inside the API JVM. `MetricsAggregator` caches the latest per-service snapshot in memory; `LogQueryService` falls back to that cache when InfluxDB is not configured. The dashboard now works in single-process mode.

### 3. Startup failures when backends weren't running
**Symptom:** API and ingestion worker crashed on boot with connection-refused errors if Elasticsearch / InfluxDB / Kafka wasn't running.

**Fix (commit `281f246`):**
  - `ElasticsearchWriter` and `InfluxDbWriter` accept their config as constructor params and disable gracefully (warn + no-op) when URLs are empty — matching the pre-existing `AlertSubscriber` pattern.
  - `AppConfig` injects storage URLs via `@Value` with empty-string defaults.
  - New `loginsight.elasticsearch.*` / `loginsight.influxdb.*` properties with env-var pass-through.
  - Result: every component is optional, the API always starts.

### 4. Default port 8080 collided with Windows `httpd.exe`
**Symptom:** Spring Boot failed to bind on port 8080 on some dev boxes.

**Fix:** Default `server.port` changed to **8081**. Documented everywhere in the README.

### 5. Hardcoded secrets in git-tracked files
**Symptom:** Security audit found InfluxDB password/token + Grafana password in `docker-compose.yml`, and an InfluxDB token in the PowerShell run scripts.

**Fix (commit `89fa37d` / 2026-05-22 audit):**
  - All secrets moved to `${ENV_VARS}` read from `.env` / `.env.ps1`.
  - `.env.example` and `.env.ps1.example` templates committed; real files gitignored.
  - Run scripts now fail with a helpful error if the env file is missing instead of silently using a baked-in token.

### 6. Public dashboard sharing enabled in Grafana
**Symptom:** `GF_FEATURE_TOGGLES_ENABLE=publicDashboards` in `docker-compose.yml` allowed unauthenticated public dashboard sharing.

**Fix:** Removed the feature toggle.

### 7. Unauthenticated simulation start/stop endpoints
**Symptom:** Anyone with network reach to port 8081 could trigger or stop the simulator.

**Fix:**
  - `SimulationController` now requires `X-Simulation-Key` header when `SIMULATION_API_KEY` env var is set.
  - Comparison uses `MessageDigest.isEqual` (constant time) to prevent timing attacks.
  - Dashboard JS stores the key in `sessionStorage`; new **🔑 Set key** button in the Simulation tab.

### 8. Kafka broker URL leaked via `/api/v1/simulation/status`
**Symptom:** The status endpoint returned the full `bootstrapServers` string, exposing internal infrastructure to the browser.

**Fix:** Replaced the URL string with a `bootstrapConfigured` boolean. Dashboard JS updated to match.

### 9. Missing HTTP security headers
**Fix:** Added a `FilterRegistrationBean` in `AppConfig` that sets `X-Content-Type-Options`, `X-Frame-Options: DENY`, `X-XSS-Protection: 0`, `Referrer-Policy`, and a `Content-Security-Policy` on every response.

### 10. Permissive CORS
**Fix:** Added a `WebMvcConfigurer` — CORS is off by default, and origins are restricted to whatever is listed in `CORS_ALLOWED_ORIGINS`.

### 11. Shell injection in `scripts/load-gen.sh`
**Symptom:** CLI args (`--service`, `--level`, `--status`, `--count`, `--rate`) were interpolated directly into JSON payloads and `curl` commands — a crafted value could break out and run arbitrary commands.

**Fix:** Each flag value is validated against a regex (`^[a-zA-Z0-9_-]+$` style) before being used. Invalid input causes an immediate exit with a clear error message.

### 12. POST /api/v1/logs accepted unbounded input
**Symptom:** No size limits on `host` or `tags`.

**Fix:** Validation in the controller: `host` ≤ 253 chars; `tags` ≤ 20 entries, key ≤ 64 chars, value ≤ 256 chars.

### 13. Spring Boot fat JAR was 12 KB
**Symptom:** `java -jar api/target/api-1.0.0-SNAPSHOT.jar` failed with `no main manifest attribute`. The JAR was only 12 KB.

**Root cause:** The `spring-boot-maven-plugin` `repackage` goal wasn't running because the build invocation missed the parent module.

**Fix:** Always build with `mvn clean package -pl api -am -DskipTests`. The `-am` flag ("also make") builds the dependencies first, and `repackage` produces the proper executable JAR. Or just use `mvn spring-boot:run` via `run-api.ps1`.

### 14. Zookeeper container stayed unhealthy
**Symptom:** `docker compose ps` showed Zookeeper as `unhealthy` even when functioning correctly.

**Root cause:** Newer Confluent images disable the `ruok` four-letter command by default.

**Fix:** Healthcheck switched to `echo srvr | nc localhost 2181 | grep -q Mode`. Already in the committed `docker-compose.yml`.

### 15. InfluxDB login worked once, then never again
**Symptom:** After changing InfluxDB credentials in the env file, the new credentials were rejected.

**Root cause:** InfluxDB `DOCKER_INFLUXDB_INIT_*` env vars only apply on the **first** container boot. They don't reconfigure an existing data volume.

**Fix:** Either set credentials before first `docker compose up`, or wipe and re-create: `docker compose down -v && docker compose up -d`.

### 16. Logs tab empty even after starting the simulator
**Symptom:** Simulation tab showed messages flowing, but the Logs tab said "No logs found".

**Common causes** (in the order to check):
  1. Simulator running but `LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS` not reachable from the API → `LogBuffer` produces no data.
  2. LogBuffer uses `auto.offset.reset=latest`, so messages produced before the API started are skipped.
  3. The Logs tab default time filter is "last 24 h" — narrow custom ranges can hide recent entries.

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
3. Check that `LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS` resolves to a reachable Kafka broker (Simulation → Configuration card shows `bootstrapConfigured: true`).
4. Widen the time range filter in the Logs tab (default is last 24 hours).

### No anomaly alerts after the spike

Since the cold-start floor and tainted-bucket fixes, the **first spike should fire within ~1 second of starting** (50-event floor at 56 events/sec spike rate). If the Alerts tab is still empty:

1. Check the API logs for `ANOMALY:` — if you see this line, the alert is firing; the issue is on the read side.
2. Check for `Suppressed (no baseline):` or `Suppressed (below 6× threshold):` log lines — these tell you exactly why the detector backed off and what `currentCount` it saw.
3. Confirm `InProcessPipeline started — topic='raw-logs'` appears at boot. Without it, no detection runs in the API process.
4. If you're relying on the standalone ingestion worker for alerts, make sure `.\run-ingestion.ps1` is running.

### Simulator Start button is disabled

The button is disabled when `bootstrapConfigured: false`. Use `.\run-api.ps1` (which sets `LOGINSIGHT_KAFKA_BOOTSTRAP_SERVERS=localhost:9092`) or set the environment variable manually.

### Simulator returns 401/403

`SIMULATION_API_KEY` is set on the server. Click **🔑 Set key** in the Simulation tab and paste the key — it's sent as `X-Simulation-Key` on every simulation request.

### InfluxDB login fails / no metrics on dashboard

The InfluxDB init credentials only apply on the first container boot. If you started InfluxDB before the env vars were set:
```powershell
docker compose down -v   # wipes volumes
docker compose up -d
```

The Dashboard metric cards also work without InfluxDB — the `InProcessPipeline` + `MetricsAggregator` keep an in-memory snapshot per service.

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

Edit `docker-compose.yml` and change the left side of the port mapping — e.g. `"19200:9200"` for Elasticsearch on port 19200. The API itself listens on 8081 (configurable via `SERVER_PORT`).

### Required env vars / .env files missing

Run scripts fail fast with a clear message. Copy the templates:
```powershell
Copy-Item .env.example .env
Copy-Item .env.ps1.example .env.ps1
```
Then fill in the values. Both files are gitignored.

---

## License

Internal demo project. See `LICENSE` if present.
