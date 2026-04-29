package com.loginsight.storage;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loginsight.common.LogEntry;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bulk-indexes {@link LogEntry} documents into Elasticsearch 8.x and supports
 * paginated search for use by the API layer and the S3 archival worker.
 *
 * <p>Index names are day-partitioned as {@code logs-YYYY.MM.dd}, aligning with
 * Elasticsearch ILM hot/warm/cold phase transitions. The ILM policy (configured
 * separately) rolls over indices at 7 days; the {@link S3ArchivalWorker} then
 * fetches those indices, uploads them to S3, and calls {@link #deleteIndex}.
 *
 * <p>Required environment variable: {@code ELASTICSEARCH_URL}
 * (e.g. {@code http://localhost:9200}).
 */
public final class ElasticsearchWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchWriter.class);
    private static final DateTimeFormatter INDEX_DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final int SEARCH_PAGE_SIZE = 1_000;

    private final ElasticsearchClient client;
    private final RestClient restClient;

    /**
     * Constructs the writer, opening a low-level HTTP connection to Elasticsearch.
     */
    public ElasticsearchWriter() {
        String url = requireEnv("ELASTICSEARCH_URL");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.restClient = RestClient.builder(HttpHost.create(url)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(mapper));
        this.client = new ElasticsearchClient(transport);
        log.info("ElasticsearchWriter connected to {}", url);
    }

    /**
     * Bulk-indexes a batch of log entries. Failures on individual documents are logged
     * and counted but do not abort the remaining documents in the batch.
     *
     * @param entries the batch to index; silently ignored when empty
     * @throws IOException on transport-level failures (network, auth, cluster unavailable)
     */
    public void bulkWrite(List<LogEntry> entries) throws IOException {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) return;

        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (LogEntry entry : entries) {
            String index = indexName(entry);
            bulk.operations(op -> op.index(idx -> idx
                    .index(index)
                    .id(entry.id())
                    .document(entry)
            ));
        }

        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            long failures = response.items().stream()
                    .filter(item -> item.error() != null)
                    .peek(item -> log.error("ES index error: id={} reason={}", item.id(),
                            item.error() != null ? item.error().reason() : "unknown"))
                    .count();
            log.warn("Bulk write: {}/{} documents failed", failures, entries.size());
        } else {
            log.debug("Bulk write OK: {} documents indexed", entries.size());
        }
    }

    /**
     * Pages through all documents in the given index using search-after semantics.
     * Suitable for the archival worker which must process potentially millions of documents.
     *
     * @param indexName the fully-qualified index name (e.g. {@code logs-2024.01.01})
     * @return all {@link LogEntry} documents found in the index
     */
    public List<LogEntry> findAllInIndex(String indexName) throws IOException {
        List<LogEntry> results = new ArrayList<>();
        List<FieldValue> searchAfter = null;

        while (true) {
            final List<FieldValue> sa = searchAfter;
            SearchResponse<LogEntry> response = client.search(s -> {
                var req = s.index(indexName)
                        .query(Query.of(q -> q.matchAll(m -> m)))
                        .size(SEARCH_PAGE_SIZE)
                        .sort(sort -> sort.field(f -> f.field("timestamp").order(SortOrder.Asc)));
                if (sa != null) req.searchAfter(sa);
                return req;
            }, LogEntry.class);

            List<Hit<LogEntry>> hits = response.hits().hits();
            if (hits.isEmpty()) break;

            hits.stream()
                    .filter(h -> h.source() != null)
                    .map(Hit::source)
                    .forEach(results::add);

            if (hits.size() < SEARCH_PAGE_SIZE) break;

            Hit<LogEntry> lastHit = hits.get(hits.size() - 1);
            searchAfter = lastHit.sort();
        }

        return results;
    }

    /**
     * Searches for log entries by time range and optional service, up to {@code limit} results.
     * Used by the REST API layer.
     */
    public List<LogEntry> search(String service, Instant from, Instant to, int limit) throws IOException {
        SearchResponse<LogEntry> response = client.search(s -> {
            var req = s.index("logs-*")
                    .size(Math.min(limit, 1_000))
                    .sort(sort -> sort.field(f -> f.field("timestamp").order(SortOrder.Desc)));

            if (service != null) {
                req.query(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("service").value(service)))
                        .filter(f -> f.range(r -> r
                                .field("timestamp")
                                .gte(JsonData.of(from.toString()))
                                .lt(JsonData.of(to.toString()))
                        ))
                ));
            } else {
                req.query(q -> q.range(r -> r
                        .field("timestamp")
                        .gte(JsonData.of(from.toString()))
                        .lt(JsonData.of(to.toString()))
                ));
            }
            return req;
        }, LogEntry.class);

        return response.hits().hits().stream()
                .filter(h -> h.source() != null)
                .map(Hit::source)
                .toList();
    }

    /**
     * Checks whether the given index exists in Elasticsearch.
     */
    public boolean indexExists(String indexName) throws IOException {
        return client.indices().exists(e -> e.index(indexName)).value();
    }

    /**
     * Deletes the named index. Called by {@link S3ArchivalWorker} after a successful S3 upload.
     */
    public void deleteIndex(String indexName) throws IOException {
        client.indices().delete(d -> d.index(indexName));
        log.info("Deleted ES index '{}' after S3 archival", indexName);
    }

    @Override
    public void close() throws IOException {
        restClient.close();
    }

    private static String indexName(LogEntry entry) {
        LocalDate date = entry.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
        return "logs-" + INDEX_DATE_FMT.format(date);
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException("Required env var not set: " + name);
        return v;
    }
}
