package com.loginsight.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loginsight.common.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background worker that implements the hot → cold archival leg of the ILM policy.
 *
 * <p>Runs on a single virtual thread once every 24 hours. For each day-partitioned
 * Elasticsearch index older than {@value ARCHIVAL_THRESHOLD_DAYS} days it:
 * <ol>
 *   <li>Pages through all documents via {@link ElasticsearchWriter#findAllInIndex}</li>
 *   <li>Serialises them as newline-delimited JSON (NDJSON)</li>
 *   <li>Uploads the NDJSON blob to S3 at
 *       {@code s3://<bucket>/logs/YYYY/MM/DD/logs-YYYY.MM.dd.ndjson}</li>
 *   <li>Deletes the Elasticsearch index to reclaim disk</li>
 * </ol>
 *
 * <p>A failure during upload aborts the delete step, ensuring no data is lost.
 * Indices are only removed from Elasticsearch after S3 confirms the write.
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code S3_BUCKET} — destination bucket name</li>
 *   <li>{@code AWS_REGION} — e.g. {@code us-east-1}</li>
 *   <li>{@code ELASTICSEARCH_URL} — inherited by {@link ElasticsearchWriter}</li>
 * </ul>
 */
public final class S3ArchivalWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3ArchivalWorker.class);

    private static final int    ARCHIVAL_THRESHOLD_DAYS = 7;
    /** Maximum days back to scan. Prevents runaway scanning on a fresh cluster. */
    private static final int    MAX_LOOKBACK_DAYS       = 365;
    private static final DateTimeFormatter INDEX_DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter S3_PATH_FMT    = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final ElasticsearchWriter esWriter;
    private final S3Client s3Client;
    private final String s3Bucket;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;

    /**
     * @param esWriter used both to fetch documents and to delete indices post-upload
     */
    public S3ArchivalWorker(ElasticsearchWriter esWriter) {
        this.esWriter  = Objects.requireNonNull(esWriter, "esWriter");
        this.s3Bucket  = requireEnv("S3_BUCKET");
        this.mapper    = new ObjectMapper().registerModule(new JavaTimeModule());
        this.s3Client  = S3Client.builder()
                .region(Region.of(requireEnv("AWS_REGION")))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("s3-archival").factory()
        );
        log.info("S3ArchivalWorker initialised: bucket={}", s3Bucket);
    }

    /**
     * Schedules the archival job to run immediately on startup, then once every 24 hours.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::runArchivalCycle, 0, 24, TimeUnit.HOURS);
        log.info("S3ArchivalWorker scheduled: archiving indices older than {} days", ARCHIVAL_THRESHOLD_DAYS);
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        s3Client.close();
    }

    private void runArchivalCycle() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        log.info("Archival cycle started — cutoff date: {}", today.minusDays(ARCHIVAL_THRESHOLD_DAYS));

        for (int daysAgo = ARCHIVAL_THRESHOLD_DAYS; daysAgo <= MAX_LOOKBACK_DAYS; daysAgo++) {
            LocalDate targetDate = today.minusDays(daysAgo);
            String indexName     = "logs-" + INDEX_DATE_FMT.format(targetDate);
            archiveIndex(indexName, targetDate);
        }

        log.info("Archival cycle complete");
    }

    private void archiveIndex(String indexName, LocalDate date) {
        try {
            if (!esWriter.indexExists(indexName)) return;

            log.info("Archiving '{}' to S3", indexName);
            List<LogEntry> entries = esWriter.findAllInIndex(indexName);

            if (entries.isEmpty()) {
                esWriter.deleteIndex(indexName);
                return;
            }

            String ndjson = toNdjson(entries);
            String s3Key  = "logs/" + S3_PATH_FMT.format(date) + "/" + indexName + ".ndjson";

            upload(s3Key, ndjson);
            // Only delete from ES after S3 confirms the write — prevents data loss on upload failure
            esWriter.deleteIndex(indexName);

            log.info("Archived {} docs from '{}' → s3://{}/{}", entries.size(), indexName, s3Bucket, s3Key);
        } catch (Exception e) {
            log.error("Failed to archive '{}': {}", indexName, e.getMessage(), e);
        }
    }

    private String toNdjson(List<LogEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder(entries.size() * 256);
        for (LogEntry entry : entries) {
            sb.append(mapper.writeValueAsString(entry)).append('\n');
        }
        return sb.toString();
    }

    private void upload(String key, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(s3Bucket)
                        .key(key)
                        .contentType("application/x-ndjson")
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes)
        );
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException("Required env var not set: " + name);
        return v;
    }
}
