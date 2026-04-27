package com.loginsight.storage;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.loginsight.common.MetricSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Writes {@link MetricSnapshot} data points to InfluxDB 2.x and queries the most
 * recent snapshot for the REST API.
 *
 * <p>Each snapshot maps to one InfluxDB point in the measurement {@code log_metrics},
 * tagged by {@code service}. The blocking write API is used so the caller receives
 * explicit confirmation of write success before committing Kafka offsets.
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code INFLUXDB_URL} — e.g. {@code http://localhost:8086}</li>
 *   <li>{@code INFLUXDB_TOKEN} — InfluxDB 2.x all-access or write-scoped token</li>
 *   <li>{@code INFLUXDB_ORG} — organisation name</li>
 *   <li>{@code INFLUXDB_BUCKET} — destination bucket</li>
 * </ul>
 */
public final class InfluxDbWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InfluxDbWriter.class);
    private static final String MEASUREMENT = "log_metrics";

    private final InfluxDBClient client;
    private final WriteApiBlocking writeApi;
    private final QueryApi queryApi;
    private final String org;
    private final String bucket;

    /** Initialises the InfluxDB client from environment variables. */
    public InfluxDbWriter() {
        String url   = requireEnv("INFLUXDB_URL");
        String token = requireEnv("INFLUXDB_TOKEN");
        this.org     = requireEnv("INFLUXDB_ORG");
        this.bucket  = requireEnv("INFLUXDB_BUCKET");

        this.client   = InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket);
        this.writeApi = client.getWriteApiBlocking();
        this.queryApi = client.getQueryApi();
        log.info("InfluxDbWriter connected to {} org={} bucket={}", url, org, bucket);
    }

    /**
     * Writes one metric snapshot to InfluxDB as a single point.
     *
     * @param snapshot the metrics to persist; must not be null
     */
    public void write(MetricSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        Point point = Point.measurement(MEASUREMENT)
                .addTag("service", snapshot.service())
                .addField("messages_per_second", snapshot.messagesPerSecond())
                .addField("error_rate",          snapshot.errorRate())
                .addField("anomaly_count",        snapshot.anomalyCount())
                .time(snapshot.timestamp().toEpochMilli(), WritePrecision.MS);

        writeApi.writePoint(bucket, org, point);
        log.debug("Wrote MetricSnapshot: service={} mps={:.1f} errorRate={:.4f}",
                snapshot.service(), snapshot.messagesPerSecond(), snapshot.errorRate());
    }

    /**
     * Returns the most recent {@link MetricSnapshot} for the given service,
     * or {@code null} if no data exists.
     *
     * <p>Uses a Flux query over the last hour; callers should cache the result
     * rather than querying per-request.
     */
    public MetricSnapshot queryLatest(String service) {
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: -1h)
                  |> filter(fn: (r) => r._measurement == "%s" and r.service == "%s")
                  |> last()
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                """, bucket, MEASUREMENT, service);

        List<FluxTable> tables = queryApi.query(flux, org);
        if (tables.isEmpty() || tables.get(0).getRecords().isEmpty()) return null;

        FluxRecord record = tables.get(0).getRecords().get(0);
        return new MetricSnapshot(
                service,
                toDouble(record.getValueByKey("messages_per_second")),
                toDouble(record.getValueByKey("error_rate")),
                toLong(record.getValueByKey("anomaly_count")),
                record.getTime() != null ? record.getTime() : Instant.now()
        );
    }

    @Override
    public void close() {
        client.close();
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException("Required env var not set: " + name);
        return v;
    }
}
