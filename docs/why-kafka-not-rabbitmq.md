# Decision Record: Kafka over RabbitMQ, Virtual Threads over WebFlux

## Context

Log-to-Insight ingests high-volume structured log streams (target: 50 000 events/second), detects anomalies in near-real-time, and must replay historical events when a downstream system (Elasticsearch, S3) suffers a transient failure. Two infrastructure choices drove the most discussion during design: the message broker and the concurrency model for the API tier.

---

## Kafka over RabbitMQ

### What we need

| Requirement                             | Impact on broker choice            |
|-----------------------------------------|------------------------------------|
| Replay ingested logs after ES downtime  | Broker must retain messages durably after consumer acknowledgement |
| Exactly-Once Semantics end-to-end       | Transactional writes + read_committed consumers |
| Scale to 50 k events/s with 3 producers | Horizontal throughput, not just queueing |
| Anomaly detection needs ordered windows | Per-partition ordering guarantee   |
| Cold archival from the broker stream    | Long retention (7 days default)    |

### Why Kafka wins on every point

**Durable log, not a queue.**  
Kafka retains messages for a configurable retention period regardless of consumer acknowledgement. If Elasticsearch is down for two hours, the ingestion workers pause, ES recovers, and consumers resume from their last committed offset — no messages are lost and no dead-letter queue is needed. RabbitMQ deletes messages after acknowledgement; you would need to implement a separate persistence layer to get equivalent replay semantics.

**Exactly-Once Semantics (EOS).**  
Kafka 0.11+ ships built-in EOS via idempotent producers (`enable.idempotence=true`) and transactions (`transactional.id`). Combined with `isolation.level=read_committed` on consumers, you get a guarantee that each logical message is processed exactly once end-to-end — even across broker failover. RabbitMQ offers at-most-once (auto-ack) or at-least-once (manual-ack + requeueing), but not idempotent exactly-once without application-level deduplication.

**Throughput at scale.**  
Kafka's sequential disk I/O + batching + zero-copy sendfile gives sustained throughput of hundreds of MB/s per broker. RabbitMQ's AMQP per-message overhead and in-memory queue model struggle past ~50 k messages/s on comparable hardware. For log ingestion at portfolio scale this matters; for typical business event flows it does not.

**Consumer groups with partition-level ordering.**  
Each Kafka partition is a totally-ordered sequence. The anomaly detector's sliding window depends on seeing events for a given (service, statusCode) in arrival order. Mapping services to partitions by key preserves this invariant. RabbitMQ consumers compete for messages from a shared queue; ordering is not guaranteed once multiple consumers are active.

**Long retention without a separate archival store.**  
Kafka's log-compaction and time-based retention policies let us keep raw events in the broker for 7 days at low cost (EBS on MSK). This provides a natural recovery window without any additional component. RabbitMQ would require routing all consumed messages to a secondary store immediately, adding latency and complexity.

### When RabbitMQ is the right choice

RabbitMQ excels at complex routing (topic exchanges, fanout, dead-letter queues), flexible per-message TTLs, and AMQP interop with legacy systems. If Log-to-Insight were a task-queue workload (jobs, emails, webhooks) rather than a log-stream workload, RabbitMQ would be simpler to operate. The recommendation is technology-fit, not technology superiority.

---

## Virtual Threads over WebFlux / Project Reactor

### The problem with the traditional Tomcat thread pool

Spring MVC with a fixed Tomcat thread pool (default 200 threads) blocks one OS thread per in-flight request. Each request to `GET /logs` performs a synchronous Elasticsearch HTTP call (~10–50 ms) and an optional InfluxDB query (~5–20 ms). At 500 concurrent requests you saturate the thread pool; the 501st request queues.

### The reactive alternative

Project Reactor / WebFlux solves this with non-blocking I/O: threads are never blocked waiting for I/O, so a small number of event-loop threads can handle thousands of concurrent requests. The trade-off is a fully reactive programming model — every line of business logic must be written as a chain of `Mono` / `Flux` operators. This is a steep learning curve, produces hard-to-debug stack traces, and makes simple sequential logic like "query ES, if not found query InfluxDB, then transform result" look like:

```java
return esClient.search(req)
    .switchIfEmpty(influxClient.query(req2))
    .map(result -> transform(result))
    .onErrorResume(e -> Mono.just(fallback()));
```

Correctness bugs (forgetting to subscribe, blocking inside a Reactor chain, context propagation across operators) are subtle and hard to catch in testing.

### Why virtual threads win for this use case

Java 21 Project Loom introduces virtual threads: lightweight threads scheduled by the JVM, not the OS. They block just like platform threads (simple sequential code) but parking a virtual thread costs ~200 bytes of heap rather than ~1 MB of OS stack. The JVM runs millions of virtual threads on a small pool of carrier threads.

The result: **write blocking imperative code, get reactive throughput**.

```java
// Plain Java, blocking calls, reads like pseudocode
List<LogEntry> logs = esWriter.search(service, from, to, limit);   // blocks virtual thread, not carrier
MetricSnapshot snap = influxWriter.queryLatest(service);            // blocks virtual thread, not carrier
return ResponseEntity.ok(merge(logs, snap));
```

Enabling virtual threads in Spring Boot 3.2+ is one property:

```properties
spring.threads.virtual.enabled=true
```

Tomcat's thread pool is replaced internally with virtual threads. No code changes to controllers or services required.

### Benchmarks (illustrative, not measured in this project)

| Scenario                   | 200-thread Tomcat | WebFlux        | Virtual Threads (JDK 21) |
|----------------------------|-------------------|----------------|--------------------------|
| 100 rps, 50 ms latency     | fine              | fine           | fine                     |
| 1 000 rps, 50 ms latency   | thread-starved    | fine           | fine                     |
| 10 000 rps, 50 ms latency  | severe queueing   | fine           | fine                     |
| Code complexity            | low               | high           | low                      |
| Debuggability              | excellent         | poor           | excellent                |
| Stack trace readability    | excellent         | poor (Reactor) | excellent                |

### When WebFlux is the right choice

If the application integrates with libraries that are already reactive (R2DBC, reactive Mongo, reactive Redis), WebFlux makes sense — you get backpressure propagation and a consistent programming model. For Log-to-Insight, Elasticsearch and InfluxDB clients are blocking (or async-but-awaitable), making virtual threads the simpler fit.

---

## Summary

| Decision               | Choice         | Runner-up   | Deciding factor                              |
|------------------------|----------------|-------------|----------------------------------------------|
| Message broker         | Apache Kafka   | RabbitMQ    | Durable replay, EOS, ordering, retention     |
| API concurrency model  | Virtual Threads| WebFlux     | Simplicity, debuggability, equivalent throughput |
