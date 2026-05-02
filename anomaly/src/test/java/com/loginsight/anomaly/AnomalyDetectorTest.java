package com.loginsight.anomaly;

import com.loginsight.common.AlertEvent;
import com.loginsight.common.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnomalyDetectorTest {

    private List<AlertEvent> sink;
    private AnomalyDetector detector;

    @BeforeEach
    void setUp() {
        sink = new ArrayList<>();
        detector = new AnomalyDetector(sink::add);
    }

    @Test
    void ignoresNonErrorEntries() {
        for (int i = 0; i < 100; i++) {
            detector.ingest(entry("svc", 200, Instant.now()));
        }
        assertTrue(sink.isEmpty());
    }

    @Test
    void doesNotFireWithoutBaseline() {
        // Even 100 errors in the current bucket alone shouldn't fire — baseline empty
        for (int i = 0; i < 100; i++) {
            detector.ingest(entry("svc", 500, Instant.now()));
        }
        assertTrue(sink.isEmpty(), "should not fire without baseline data");
    }

    @Test
    void firesWhenSpikeExceeds6xBaseline() {
        long now = System.currentTimeMillis();
        // baseline: 2 events per minute over 4 prior minutes
        for (int minuteAgo = 4; minuteAgo >= 1; minuteAgo--) {
            long t = now - minuteAgo * 60_000L;
            detector.ingest(entry("svc", 500, Instant.ofEpochMilli(t)));
            detector.ingest(entry("svc", 500, Instant.ofEpochMilli(t + 1000)));
        }
        // current minute: 13 events (>6 * 2 = 12)
        for (int i = 0; i < 13; i++) {
            detector.ingest(entry("svc", 500, Instant.now()));
        }
        assertFalse(sink.isEmpty(), "expected an alert");
        AlertEvent a = sink.get(sink.size() - 1);
        assertEquals("svc", a.service());
        assertEquals(500,    a.statusCode());
        assertTrue(a.spikePercentage() >= 500.0);
    }

    @Test
    void doesNotFireBelowThreshold() {
        long now = System.currentTimeMillis();
        for (int minuteAgo = 4; minuteAgo >= 1; minuteAgo--) {
            long t = now - minuteAgo * 60_000L;
            for (int i = 0; i < 5; i++) {
                detector.ingest(entry("svc", 500, Instant.ofEpochMilli(t + i)));
            }
        }
        // baseline avg = 5/min; threshold = 30; current = 20 -> below threshold
        for (int i = 0; i < 20; i++) {
            detector.ingest(entry("svc", 500, Instant.now()));
        }
        assertTrue(sink.isEmpty());
    }

    @Test
    void perServicePerStatusIsolation() {
        long now = System.currentTimeMillis();
        for (int minuteAgo = 4; minuteAgo >= 1; minuteAgo--) {
            long t = now - minuteAgo * 60_000L;
            detector.ingest(entry("svc-a", 500, Instant.ofEpochMilli(t)));
            detector.ingest(entry("svc-a", 500, Instant.ofEpochMilli(t + 1)));
        }
        for (int i = 0; i < 50; i++) {
            detector.ingest(entry("svc-b", 500, Instant.now()));
        }
        // svc-b had no baseline; svc-a's window untouched by svc-b
        assertTrue(sink.isEmpty());
    }

    @Test
    void severityIsCriticalAtTenfoldSpike() {
        long now = System.currentTimeMillis();
        for (int minuteAgo = 4; minuteAgo >= 1; minuteAgo--) {
            long t = now - minuteAgo * 60_000L;
            for (int i = 0; i < 2; i++) {
                detector.ingest(entry("svc", 500, Instant.ofEpochMilli(t + i)));
            }
        }
        // baseline avg = 2; trigger 30 in current bucket → spike % = 1400 → CRITICAL
        for (int i = 0; i < 30; i++) {
            detector.ingest(entry("svc", 500, Instant.now()));
        }
        assertFalse(sink.isEmpty());
        assertEquals(AlertEvent.Severity.CRITICAL, sink.get(sink.size() - 1).severity());
    }

    @Test
    void getAlertsForServiceReturnsOnlyMatching() {
        long now = System.currentTimeMillis();
        // produce an alert for svc-a
        for (int minuteAgo = 4; minuteAgo >= 1; minuteAgo--) {
            long t = now - minuteAgo * 60_000L;
            detector.ingest(entry("svc-a", 500, Instant.ofEpochMilli(t)));
            detector.ingest(entry("svc-a", 500, Instant.ofEpochMilli(t + 1)));
        }
        for (int i = 0; i < 13; i++) detector.ingest(entry("svc-a", 500, Instant.now()));

        assertFalse(detector.getAlertsForService("svc-a").isEmpty());
        assertTrue(detector.getAlertsForService("svc-b").isEmpty());
    }

    private static LogEntry entry(String service, int status, Instant ts) {
        return new LogEntry("id-" + Math.random(), service, status >= 400 ? "ERROR" : "INFO",
                status, "msg", "host", "trace", ts, Map.of());
    }
}
