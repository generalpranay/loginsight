package com.loginsight.storage;

import com.loginsight.common.LogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3ArchivalWorkerTest {

    private static final String BUCKET   = "test-bucket";
    private static final DateTimeFormatter INDEX_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter PATH_FMT  = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private ElasticsearchWriter mockEsWriter;
    private S3Client            mockS3;
    private S3ArchivalWorker    worker;

    @BeforeEach
    void setUp() throws Exception {
        mockEsWriter = mock(ElasticsearchWriter.class);
        mockS3       = mock(S3Client.class);
        when(mockEsWriter.indexExists(anyString())).thenReturn(false);
        worker = new S3ArchivalWorker(mockEsWriter, mockS3, BUCKET);
    }

    @AfterEach
    void tearDown() {
        worker.close();
    }

    @Test
    void archivalCycle_skipsIndexThatDoesNotExist() throws Exception {
        worker.runArchivalCycle();
        verify(mockEsWriter, never()).findAllInIndex(anyString());
        verify(mockS3,       never()).putObject(any(PutObjectRequest.class), anyRequestBody());
    }

    @Test
    void archivalCycle_uploadsNdjsonThenDeletesIndex() throws Exception {
        String targetIndex = indexName(7);
        when(mockEsWriter.indexExists(targetIndex)).thenReturn(true);
        when(mockEsWriter.findAllInIndex(targetIndex)).thenReturn(List.of(logEntry("id1")));

        worker.runArchivalCycle();

        InOrder order = inOrder(mockS3, mockEsWriter);
        order.verify(mockS3).putObject(any(PutObjectRequest.class), anyRequestBody());
        order.verify(mockEsWriter).deleteIndex(targetIndex);
    }

    @Test
    void archivalCycle_doesNotDeleteIndexIfS3UploadFails() throws Exception {
        String targetIndex = indexName(7);
        when(mockEsWriter.indexExists(targetIndex)).thenReturn(true);
        when(mockEsWriter.findAllInIndex(targetIndex)).thenReturn(List.of(logEntry("id1")));
        doThrow(new RuntimeException("S3 unavailable"))
                .when(mockS3).putObject(any(PutObjectRequest.class), anyRequestBody());

        assertDoesNotThrow(() -> worker.runArchivalCycle());
        verify(mockEsWriter, never()).deleteIndex(anyString());
    }

    @Test
    void archivalCycle_emptyIndexIsDeletedWithoutS3Upload() throws Exception {
        String targetIndex = indexName(10);
        when(mockEsWriter.indexExists(targetIndex)).thenReturn(true);
        when(mockEsWriter.findAllInIndex(targetIndex)).thenReturn(List.of());

        worker.runArchivalCycle();

        verify(mockS3, never()).putObject(any(PutObjectRequest.class), anyRequestBody());
        verify(mockEsWriter).deleteIndex(targetIndex);
    }

    @Test
    void archivalCycle_s3KeyUsesYyyySlashMmSlashDdPathAndNdjsonExtension() throws Exception {
        LocalDate targetDate  = LocalDate.now(ZoneOffset.UTC).minusDays(7);
        String    targetIndex = "logs-" + INDEX_FMT.format(targetDate);
        when(mockEsWriter.indexExists(targetIndex)).thenReturn(true);
        when(mockEsWriter.findAllInIndex(targetIndex)).thenReturn(List.of(logEntry("id1")));

        worker.runArchivalCycle();

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockS3).putObject(captor.capture(), anyRequestBody());

        String expectedKey = "logs/" + PATH_FMT.format(targetDate) + "/" + targetIndex + ".ndjson";
        assertEquals(expectedKey, captor.getValue().key());
        assertEquals(BUCKET,      captor.getValue().bucket());
    }

    @Test
    void archivalCycle_onlyArchivesIndicesOlderThan7Days() throws Exception {
        // An index 6 days old is below the threshold and must not be processed
        String tooRecent = indexName(6);
        when(mockEsWriter.indexExists(tooRecent)).thenReturn(true);

        worker.runArchivalCycle();

        verify(mockEsWriter, never()).findAllInIndex(tooRecent);
        verify(mockS3,       never()).putObject(any(PutObjectRequest.class), anyRequestBody());
    }

    // — helpers —

    /**
     * Returns a typed {@code RequestBody} matcher.
     * The explicit return type guides the compiler to pick
     * {@code putObject(PutObjectRequest, RequestBody)} over the {@code Path}-based overloads.
     */
    private static RequestBody anyRequestBody() {
        return any();
    }

    private static String indexName(int daysAgo) {
        return "logs-" + INDEX_FMT.format(LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo));
    }

    private static LogEntry logEntry(String id) {
        return new LogEntry(id, "svc", "INFO", 200, "msg", "host", "trace", Instant.now(), Map.of());
    }
}
