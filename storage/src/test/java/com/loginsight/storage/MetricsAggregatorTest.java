package com.loginsight.storage;

import com.loginsight.common.LogEntry;
import com.loginsight.common.MetricSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MetricsAggregatorTest {

    private InfluxDbWriter mockWriter;
    private MetricsAggregator aggregator;

    @BeforeEach
    void setUp() {
        mockWriter  = mock(InfluxDbWriter.class);
        aggregator  = new MetricsAggregator(mockWriter, 10);
    }

    @Test
    void recordsTotalAndErrorCountsCorrectly() {
        aggregator.record(entry("svc", 200));
        aggregator.record(entry("svc", 400));
        aggregator.record(entry("svc", 500));
        aggregator.flush();

        ArgumentCaptor<MetricSnapshot> captor = ArgumentCaptor.forClass(MetricSnapshot.class);
        verify(mockWriter).write(captor.capture());
        MetricSnapshot snap = captor.getValue();
        assertEquals("svc",      snap.service());
        assertEquals(0.3,        snap.messagesPerSecond(), 1e-9); // 3 / 10s interval
        assertEquals(2.0 / 3.0,  snap.errorRate(),         1e-9);
        assertEquals(0,          snap.anomalyCount());
    }

    @Test
    void countersResetToZeroAfterEachFlush() {
        aggregator.record(entry("svc", 200));
        aggregator.flush(); // first flush: 1 message → write() called

        aggregator.flush(); // second flush: counters are zero → write() NOT called

        verify(mockWriter, times(1)).write(any()); // only once total
    }

    @Test
    void anomalyCounterIsTrackedIndependentlyOfLogEntries() {
        aggregator.record(entry("svc", 200));
        aggregator.recordAnomaly("svc");
        aggregator.recordAnomaly("svc");
        aggregator.flush();

        ArgumentCaptor<MetricSnapshot> captor = ArgumentCaptor.forClass(MetricSnapshot.class);
        verify(mockWriter).write(captor.capture());
        assertEquals(2, captor.getValue().anomalyCount());
    }

    @Test
    void flushIsNoOpWhenNoEventsRecorded() {
        aggregator.flush();
        verify(mockWriter, never()).write(any());
    }

    @Test
    void errorRateIsZeroWhenAllEntriesAreSuccesses() {
        aggregator.record(entry("svc", 200));
        aggregator.record(entry("svc", 201));
        aggregator.flush();

        ArgumentCaptor<MetricSnapshot> captor = ArgumentCaptor.forClass(MetricSnapshot.class);
        verify(mockWriter).write(captor.capture());
        assertEquals(0.0, captor.getValue().errorRate(), 1e-9);
    }

    @Test
    void errorRateCapsAtOneWhenAllEntriesAreErrors() {
        for (int i = 0; i < 5; i++) aggregator.record(entry("svc", 500));
        aggregator.flush();

        ArgumentCaptor<MetricSnapshot> captor = ArgumentCaptor.forClass(MetricSnapshot.class);
        verify(mockWriter).write(captor.capture());
        assertEquals(1.0, captor.getValue().errorRate(), 1e-9);
    }

    @Test
    void closeTriggersOneFinalFlushBeforeShuttingDown() {
        aggregator.record(entry("svc", 200));
        aggregator.close();

        verify(mockWriter, atLeastOnce()).write(any(MetricSnapshot.class));
    }

    @Test
    void multipleServicesAreTrackedIndependently() {
        aggregator.record(entry("svc-a", 200));
        aggregator.record(entry("svc-a", 500));
        aggregator.record(entry("svc-b", 200));
        aggregator.flush();

        ArgumentCaptor<MetricSnapshot> captor = ArgumentCaptor.forClass(MetricSnapshot.class);
        verify(mockWriter, times(2)).write(captor.capture());
        Map<String, MetricSnapshot> byService = captor.getAllValues().stream()
                .collect(toMap(MetricSnapshot::service, s -> s));
        assertEquals(0.5, byService.get("svc-a").errorRate(), 1e-9);
        assertEquals(0.0, byService.get("svc-b").errorRate(), 1e-9);
    }

    @Test
    void recordAnomalyCreatesCounterForNewService() {
        aggregator.recordAnomaly("new-svc");
        aggregator.flush();

        ArgumentCaptor<MetricSnapshot> captor = ArgumentCaptor.forClass(MetricSnapshot.class);
        verify(mockWriter).write(captor.capture());
        assertEquals("new-svc", captor.getValue().service());
        assertEquals(1,          captor.getValue().anomalyCount());
    }

    // — helper —

    private static LogEntry entry(String service, int status) {
        return new LogEntry("id-" + Math.random(), service,
                status >= 400 ? "ERROR" : "INFO", status,
                "msg", "host", "trace", Instant.now(), Map.of());
    }
}
