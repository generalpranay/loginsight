package com.loginsight.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogEntryTest {

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class,
                () -> new LogEntry("", "svc", "INFO", 200, "m", "h", "t", Instant.now(), Map.of()));
    }

    @Test
    void rejectsBlankService() {
        assertThrows(IllegalArgumentException.class,
                () -> new LogEntry("id", " ", "INFO", 200, "m", "h", "t", Instant.now(), Map.of()));
    }

    @Test
    void rejectsNullTimestamp() {
        assertThrows(IllegalArgumentException.class,
                () -> new LogEntry("id", "svc", "INFO", 200, "m", "h", "t", null, Map.of()));
    }

    @Test
    void nullTagsDefaultsToEmptyMap() {
        LogEntry e = new LogEntry("id", "svc", "INFO", 200, "m", "h", "t", Instant.now(), null);
        assertEquals(Map.of(), e.tags());
    }

    @Test
    void tagsIsImmutable() {
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("k", "v");
        LogEntry e = new LogEntry("id", "svc", "INFO", 200, "m", "h", "t", Instant.now(), mutable);
        assertThrows(UnsupportedOperationException.class, () -> e.tags().put("k2", "v2"));
    }

    @Test
    void isErrorAndIsServerError() {
        LogEntry ok    = new LogEntry("a", "s", "INFO",  200, "m", "h", "t", Instant.now(), null);
        LogEntry warn  = new LogEntry("b", "s", "WARN",  404, "m", "h", "t", Instant.now(), null);
        LogEntry fatal = new LogEntry("c", "s", "ERROR", 500, "m", "h", "t", Instant.now(), null);

        assertFalse(ok.isError());
        assertTrue(warn.isError());
        assertFalse(warn.isServerError());
        assertTrue(fatal.isError());
        assertTrue(fatal.isServerError());
    }
}
