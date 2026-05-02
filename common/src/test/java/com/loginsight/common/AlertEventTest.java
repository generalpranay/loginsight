package com.loginsight.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AlertEventTest {

    @Test
    void summaryContainsKeyFields() {
        AlertEvent a = new AlertEvent(
                "alert-1", "checkout", 500, 2.0, 14.0, 600.0,
                Instant.parse("2024-01-15T10:30:00Z"), AlertEvent.Severity.WARNING);
        String summary = a.toSummary();
        assertTrue(summary.contains("checkout"));
        assertTrue(summary.contains("500"));
        assertTrue(summary.contains("WARNING"));
        assertTrue(summary.contains("600"));
    }

    @Test
    void rejectsBlankAlertId() {
        assertThrows(IllegalArgumentException.class,
                () -> new AlertEvent("", "s", 500, 1.0, 6.0, 500.0, Instant.now(), AlertEvent.Severity.WARNING));
    }

    @Test
    void rejectsBlankService() {
        assertThrows(IllegalArgumentException.class,
                () -> new AlertEvent("id", "", 500, 1.0, 6.0, 500.0, Instant.now(), AlertEvent.Severity.WARNING));
    }

    @Test
    void rejectsNullDetectedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new AlertEvent("id", "s", 500, 1.0, 6.0, 500.0, null, AlertEvent.Severity.WARNING));
    }
}
