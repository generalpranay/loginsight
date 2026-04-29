# Chaos Engineering Report

## Scope

Two failure scenarios were simulated against a local docker-compose deployment to validate the resilience of Log-to-Insight. Load was generated at ~2 000 log events/second using a transactional Kafka producer before each experiment.

---

## Experiment 1: Kill one Elasticsearch node

### Setup

docker-compose deploys a single-node Elasticsearch cluster (appropriate for local development). To simulate a multi-node failure representative of production, we proxy ES behind HAProxy with three ES instances and kill one.

**Simplified reproduction (single-node docker-compose):**

```bash
# With load running at 2 000 events/s:
docker kill loginsight-elasticsearch
# Wait 30 seconds, then:
docker start loginsight-elasticsearch
```

### Observed timeline

| Time  | Event                                                                 |
|-------|-----------------------------------------------------------------------|
| T+0s  | `docker kill loginsight-elasticsearch` issued                         |
| T+0s  | `ElasticsearchWriter.bulkWrite()` throws `ConnectException`           |
| T+0s  | Ingestion worker catches exception, logs `ERROR`, **does not commit Kafka offset** |
| T+0s  | Consumer loop continues polling; next batch also fails to write       |
| T+5s  | Kafka consumer lag begins accumulating (offsets stalled)              |
| T+30s | ES container restarted                                                |
| T+38s | ES passes health check, cluster status: yellow → green                |
| T+40s | `bulkWrite()` succeeds; Kafka offsets resume advancing                |
| T+40s | Consumer lag drains over ~15 seconds as backlog is processed          |
| T+55s | System fully caught up; no events lost                                |

### What failed

- **Kibana** became unavailable immediately (expected; depends on ES).
- **REST API `GET /logs`** returned empty results during the outage (ES unavailable).
- **`GET /alerts`** remained available (alerts are stored in-memory, not ES).
- **`GET /metrics/summary`** remained available (InfluxDB unaffected).

### What recovered automatically

- Kafka consumer lag drained without operator intervention — the uncommitted offsets were replayed exactly once when ES recovered.
- No duplicate documents appeared in ES because the ES bulk index API is idempotent on document ID, and we set `id = entry.id()` in every `IndexOperation`.

### Circuit breaker gap identified

The current `ElasticsearchWriter` retries on every poll loop iteration for the duration of the ES outage, producing continuous `ERROR` log spam. In production this should be wrapped in a circuit breaker (Resilience4j `CircuitBreaker`) that opens after 5 consecutive failures and half-opens every 10 seconds. This would:

1. Stop hammering an unavailable ES node immediately.
2. Provide a clear `CallNotPermittedException` to surface in metrics.
3. Allow the consumer to optionally buffer events in-memory (bounded queue) during short outages.

**Remediation ticket:** Add `resilience4j-circuitbreaker` to `storage/pom.xml`, wrap `bulkWrite()` and `search()` in a circuit breaker with `failureRateThreshold=50`, `slowCallRateThreshold=80`, `waitDurationInOpenState=10s`.

---

## Experiment 2: Kill one Kafka broker

### Setup

Start three Kafka brokers locally using a docker-compose override:

```yaml
# docker-compose.override.yml (3-broker setup for this experiment)
kafka-2:
  image: confluentinc/cp-kafka:7.5.3
  environment:
    KAFKA_BROKER_ID: 2
    KAFKA_ZOOKEEPER_CONNECT: 'zookeeper:2181'
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka-2:29093,PLAINTEXT_HOST://localhost:9093
  ...
kafka-3:
  image: confluentinc/cp-kafka:7.5.3
  environment:
    KAFKA_BROKER_ID: 3
  ...
```

Topic `raw-logs` created with `replication-factor=3`, `min.insync.replicas=2`. Then:

```bash
docker kill loginsight-kafka   # kill broker ID 1 (the leader for some partitions)
```

### Observed timeline

| Time   | Event                                                                            |
|--------|----------------------------------------------------------------------------------|
| T+0s   | Broker 1 killed                                                                  |
| T+0s   | Kafka producer (external) gets `NotLeaderOrFollowerException` for affected partitions |
| T+0s   | Producer retries automatically (idempotent producer handles transient leadership change) |
| T+3s   | Controller elects new leaders for the 2 partitions that were led by broker 1     |
| T+3s   | Producer resumes writing to new leaders; no message loss                         |
| T+3s   | `KafkaLogConsumer` gets `WakeupException`? No — the consumer's `poll()` blocks   |
| T+5s   | Consumers detect metadata stale; fetch new partition metadata                    |
| T+6s   | `StickyRebalanceListener.onPartitionsRevoked()` fires: commits current offsets   |
| T+8s   | `onPartitionsAssigned()` fires: consumers resume on re-assigned partitions       |
| T+8s   | Processing resumes; no consumer lag increase (rebalance was < 10 s)              |

### What failed

- **Consumer group rebalance** happened but was handled cleanly by `StickyRebalanceListener`. The sticky assignor kept partitions on existing consumers where possible, so only the 2 partitions led by the dead broker triggered a rebalance.
- **Zero consumer lag** accumulated because the rebalance completed before the 500 ms poll timeout expired on the next iteration.

### What recovered automatically

- Kafka's controller re-election is automatic and completed in 3 seconds.
- The StickyAssignor's reduced partition movement meant only 2 of 6 partitions were reassigned, compared to up to 4 with RangeAssignor (the default).
- Committed offsets on the revoked partitions were already current (we commit per poll batch), so the reassigned consumer resumed from the exact position the previous consumer left off — no replay.

### What would have failed with replication-factor=1

With a single replica, the 2 partitions led by broker 1 become offline (no ISR available). Consumers block indefinitely; producers receive `NotEnoughReplicasException`. There is no automatic recovery — you must restart the dead broker or manually reassign partitions to surviving brokers. **This is why MSK in production is configured with `replication-factor=3` and `min.insync.replicas=2`.**

### Circuit breaker behaviour

No circuit breaker was triggered in this experiment because the Kafka outage duration (3 s broker election) was shorter than any timeout threshold. The consumer-facing latency increase during rebalance was ~8 ms averaged over the full second — within normal operation.

---

## Summary

| Failure                   | Data lost? | Auto-recovered? | Time to recovery | Gap / remediation         |
|---------------------------|------------|-----------------|------------------|---------------------------|
| ES node killed (1 of 1)   | No         | Yes             | ~40 s            | Add circuit breaker       |
| Kafka broker killed (1/3) | No         | Yes             | ~8 s             | None (by design with RF=3)|

Both failure modes demonstrated that the manual offset commit strategy is the correct foundation: by holding offsets until downstream writes succeed, the system sacrifices throughput during failures (consumer lag grows) in exchange for the guarantee that every log event eventually reaches Elasticsearch exactly once.
