package com.loginsight.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MetricSnapshotTest {

    @Test
    void rejectsNegativeMps() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetricSnapshot("svc", -1.0, 0.1, 0, Instant.now()));
    }

    @Test
    void rejectsErrorRateOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetricSnapshot("svc", 10.0, 1.5, 0, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new MetricSnapshot("svc", 10.0, -0.1, 0, Instant.now()));
    }

    @Test
    void rejectsNegativeAnomalyCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetricSnapshot("svc", 10.0, 0.1, -1, Instant.now()));
    }

    @Test
    void acceptsValidSnapshot() {
        MetricSnapshot m = new MetricSnapshot("svc", 100.0, 0.05, 3, Instant.now());
        assertEquals("svc", m.service());
        assertEquals(0.05, m.errorRate());
    }
}
