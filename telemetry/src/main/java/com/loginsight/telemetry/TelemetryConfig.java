package com.loginsight.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bootstraps the OpenTelemetry SDK and owns all custom metric instruments used by
 * the Log-to-Insight platform.
 *
 * <p>Metrics are exported via a pull-based Prometheus HTTP server (default port 9464),
 * which Prometheus scrapes on its standard interval. Call {@link #initialize()} once
 * at application startup; the returned instance is safe to share across threads.
 *
 * <p>Custom instruments:
 * <ul>
 *   <li>{@code loginsight.messages_processed_total} — monotonic counter, tagged by service</li>
 *   <li>{@code loginsight.anomaly_detected_total} — monotonic counter, tagged by service + status_code</li>
 *   <li>{@code loginsight.messages_processed_per_second} — observable gauge, updated by the consumer loop</li>
 *   <li>{@code loginsight.active_anomalies} — observable gauge tracking unresolved anomaly count</li>
 * </ul>
 *
 * <p>Required environment variable: {@code SERVICE_NAME} (default {@code loginsight}).
 * Optional: {@code OTEL_PROMETHEUS_PORT} (default {@code 9464}).
 */
public final class TelemetryConfig implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConfig.class);

    private static final AttributeKey<String> SERVICE_KEY   = AttributeKey.stringKey("service");
    private static final AttributeKey<Long>   STATUS_KEY    = AttributeKey.longKey("status_code");

    private final OpenTelemetrySdk sdk;
    private final LongCounter messagesProcessedCounter;
    private final LongCounter anomalyDetectedCounter;
    private final AtomicReference<Double> currentThroughput = new AtomicReference<>(0.0);
    private final AtomicLong activeAnomalyCount = new AtomicLong(0);

    // Held to prevent GC of observable instruments
    @SuppressWarnings("FieldCanBeLocal")
    private final ObservableDoubleGauge throughputGauge;
    @SuppressWarnings("FieldCanBeLocal")
    private final ObservableDoubleGauge activeAnomalyGauge;

    private TelemetryConfig(OpenTelemetrySdk sdk) {
        this.sdk = sdk;
        Meter meter = sdk.getMeter("com.loginsight");

        this.messagesProcessedCounter = meter.counterBuilder("loginsight.messages_processed_total")
                .setDescription("Total log messages successfully processed from Kafka")
                .setUnit("{messages}")
                .build();

        this.anomalyDetectedCounter = meter.counterBuilder("loginsight.anomaly_detected_total")
                .setDescription("Total anomaly alerts fired by the sliding-window detector")
                .setUnit("{anomalies}")
                .build();

        this.throughputGauge = meter.gaugeBuilder("loginsight.messages_processed_per_second")
                .setDescription("Current ingestion throughput in messages per second")
                .setUnit("{messages}/s")
                .buildWithCallback(obs -> obs.record(currentThroughput.get(), Attributes.empty()));

        this.activeAnomalyGauge = meter.gaugeBuilder("loginsight.active_anomalies")
                .setDescription("Number of currently active (unresolved) anomaly alerts")
                .setUnit("{anomalies}")
                .buildWithCallback(obs -> obs.record((double) activeAnomalyCount.get(), Attributes.empty()));
    }

    /**
     * Initialises the SDK with a Prometheus HTTP exporter and registers all instruments.
     * Must be called before any telemetry is recorded.
     */
    public static TelemetryConfig initialize() {
        int port = Integer.parseInt(System.getenv().getOrDefault("OTEL_PROMETHEUS_PORT", "9464"));
        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "loginsight");

        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"),    serviceName,
                        AttributeKey.stringKey("service.version"), "1.0.0"
                ))
        );

        PrometheusHttpServer prometheusExporter = PrometheusHttpServer.builder()
                .setPort(port)
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(prometheusExporter)
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .buildAndRegisterGlobal();

        log.info("OpenTelemetry SDK initialised — Prometheus exporter on :{}", port);
        return new TelemetryConfig(sdk);
    }

    /** Increments the processed-messages counter for the given service. */
    public void recordMessagesProcessed(long count, String service) {
        messagesProcessedCounter.add(count, Attributes.of(SERVICE_KEY, service));
    }

    /**
     * Increments the anomaly counter and the active-anomaly gauge.
     * Call {@link #resolveAnomaly()} when the condition clears.
     */
    public void recordAnomalyDetected(String service, int statusCode) {
        anomalyDetectedCounter.add(1, Attributes.of(SERVICE_KEY, service, STATUS_KEY, (long) statusCode));
        activeAnomalyCount.incrementAndGet();
    }

    /** Decrements active-anomaly gauge when an alert is resolved. */
    public void resolveAnomaly() {
        activeAnomalyCount.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** Updates the throughput gauge. Call once per consumer poll cycle. */
    public void updateThroughput(double messagesPerSecond) {
        currentThroughput.set(messagesPerSecond);
    }

    public OpenTelemetry getOpenTelemetry() {
        return sdk;
    }

    @Override
    public void close() {
        sdk.close();
    }
}
