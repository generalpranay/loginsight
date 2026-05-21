package com.loginsight.storage;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import com.loginsight.common.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class ElasticsearchWriterTest {

    private ElasticsearchClient mockClient;
    private ElasticsearchWriter writer;

    @BeforeEach
    void setUp() {
        mockClient = mock(ElasticsearchClient.class);
        writer     = new ElasticsearchWriter(mockClient);
    }

    // ── disabled-mode tests (no network, no mock client needed) ──────────────

    @Test
    void disabledWriter_bulkWriteIsNoOp() throws Exception {
        try (ElasticsearchWriter disabled = new ElasticsearchWriter("")) {
            assertDoesNotThrow(() -> disabled.bulkWrite(List.of(entry("id1", Instant.now()))));
        }
    }

    @Test
    void disabledWriter_searchReturnsEmptyList() throws Exception {
        try (ElasticsearchWriter disabled = new ElasticsearchWriter("")) {
            assertTrue(disabled.search("svc", Instant.now().minusSeconds(60), Instant.now(), 10).isEmpty());
        }
    }

    @Test
    void disabledWriter_findAllInIndexReturnsEmptyList() throws Exception {
        try (ElasticsearchWriter disabled = new ElasticsearchWriter("")) {
            assertTrue(disabled.findAllInIndex("logs-2024.01.01").isEmpty());
        }
    }

    @Test
    void disabledWriter_indexExistsReturnsFalse() throws Exception {
        try (ElasticsearchWriter disabled = new ElasticsearchWriter("")) {
            assertFalse(disabled.indexExists("logs-2024.01.01"));
        }
    }

    // ── index name format test (no I/O needed) ───────────────────────────────

    @Test
    void indexNameIsDayPartitionedInYyyyDotMmDotDdFormat() {
        Instant ts    = Instant.parse("2024-03-15T23:59:59Z");
        LogEntry e    = entry("id1", ts);
        assertEquals("logs-2024.03.15", ElasticsearchWriter.indexName(e));
    }

    // ── enabled-mode bulk-write tests ────────────────────────────────────────

    @Test
    void bulkWrite_forwardsDocumentsToClient() throws Exception {
        BulkResponse mockResponse = mock(BulkResponse.class);
        when(mockResponse.errors()).thenReturn(false);
        // Cast to BulkRequest to disambiguate from the Function<> overload
        doReturn(mockResponse).when(mockClient).bulk((BulkRequest) anyBulkRequest());

        writer.bulkWrite(List.of(entry("id1", Instant.parse("2024-03-15T00:00:00Z"))));

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(mockClient).bulk((BulkRequest) captor.capture());
        assertEquals(1, captor.getValue().operations().size());
        assertEquals("logs-2024.03.15", captor.getValue().operations().get(0).index().index());
    }

    @Test
    void bulkWrite_emptyBatchSkipsClientCall() throws Exception {
        writer.bulkWrite(List.of());
        verify(mockClient, never()).bulk((BulkRequest) anyBulkRequest());
    }

    @Test
    void bulkWrite_continuesWhenSomeDocumentsFail() throws Exception {
        ErrorCause       error = mock(ErrorCause.class);
        when(error.reason()).thenReturn("mapping conflict");
        BulkResponseItem item  = mock(BulkResponseItem.class);
        when(item.error()).thenReturn(error);
        BulkResponse mockResponse = mock(BulkResponse.class);
        when(mockResponse.errors()).thenReturn(true);
        when(mockResponse.items()).thenReturn(List.of(item));

        doReturn(mockResponse).when(mockClient).bulk((BulkRequest) anyBulkRequest());

        // Partial failures are logged, not thrown
        assertDoesNotThrow(() -> writer.bulkWrite(List.of(entry("id1", Instant.now()))));
    }

    // ── enabled-mode pagination tests ────────────────────────────────────────

    @Test
    void findAllInIndex_singlePageEndsWhenHitsFewerThan1000() throws Exception {
        SearchResponse<LogEntry> response = mockSearchPage(List.of(mockHit("e1"), mockHit("e2")));
        doReturn(response).when(mockClient).search(anyFunction(), any(Class.class));

        List<LogEntry> results = writer.findAllInIndex("logs-2024.01.01");

        assertEquals(2, results.size());
        verify(mockClient, times(1)).search(anyFunction(), any(Class.class));
    }

    @Test
    void findAllInIndex_paginatesWhenFirstPageIsFull() throws Exception {
        // Page 1: exactly 1 000 hits — triggers a second request
        List<Hit<LogEntry>> fullPage = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Hit<LogEntry> h = mockHit("id-" + i);
            when(h.sort()).thenReturn(List.of(mock(FieldValue.class)));
            fullPage.add(h);
        }
        SearchResponse<LogEntry> page1 = mockSearchPage(fullPage);
        SearchResponse<LogEntry> page2 = mockSearchPage(List.of());

        doReturn(page1).doReturn(page2).when(mockClient).search(anyFunction(), any(Class.class));

        List<LogEntry> results = writer.findAllInIndex("logs-2024.01.01");

        assertEquals(1000, results.size());
        verify(mockClient, times(2)).search(anyFunction(), any(Class.class));
    }

    // — helpers —

    /** Typed wrapper so the compiler unambiguously picks bulk(BulkRequest). */
    private static BulkRequest anyBulkRequest() {
        return any();
    }

    /** Typed wrapper so the compiler picks search(Function, Class) over search(SearchRequest, …). */
    private static Function anyFunction() {
        return any(Function.class);
    }

    private static SearchResponse<LogEntry> mockSearchPage(List<Hit<LogEntry>> hits) {
        SearchResponse<LogEntry> response = mock(SearchResponse.class);
        HitsMetadata<LogEntry>   meta     = mock(HitsMetadata.class);
        when(response.hits()).thenReturn(meta);
        when(meta.hits()).thenReturn(hits);
        return response;
    }

    private static Hit<LogEntry> mockHit(String id) {
        Hit<LogEntry> h = mock(Hit.class);
        when(h.source()).thenReturn(entry(id, Instant.now()));
        when(h.sort()).thenReturn(List.of());
        return h;
    }

    private static LogEntry entry(String id, Instant ts) {
        return new LogEntry(id, "svc", "INFO", 200, "msg", "host", "trace", ts, Map.of());
    }
}
