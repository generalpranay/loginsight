package com.loginsight.api;

import com.loginsight.common.AlertEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "loginsight.kafka.enabled=false",
        "ELASTICSEARCH_URL=http://localhost:9200",
        "INFLUXDB_URL=http://localhost:8086",
        "INFLUXDB_TOKEN=t",
        "INFLUXDB_ORG=o",
        "INFLUXDB_BUCKET=b"
})
@AutoConfigureMockMvc
class LogIngestionControllerTest {

    @Autowired MockMvc mvc;

    @MockBean LogQueryService logQueryService;
    @MockBean AlertSubscriber alertSubscriber;
    @MockBean com.loginsight.storage.ElasticsearchWriter elasticsearchWriter;
    @MockBean com.loginsight.storage.InfluxDbWriter influxDbWriter;
    @MockBean com.loginsight.telemetry.TelemetryConfig telemetryConfig;

    @Test
    void healthReturnsUp() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void rejectsInvalidServiceName() throws Exception {
        mvc.perform(get("/api/v1/logs").param("service", "bad name; DROP"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidTimestamp() throws Exception {
        mvc.perform(get("/api/v1/logs").param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void alertsForServiceCallsSubscriber() throws Exception {
        when(alertSubscriber.getForService("checkout"))
                .thenReturn(List.of(new AlertEvent(
                        "a1", "checkout", 500, 1.0, 6.0, 500.0,
                        Instant.now(), AlertEvent.Severity.WARNING)));

        mvc.perform(get("/api/v1/alerts").param("service", "checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].service").value("checkout"));
    }

    @Test
    void metricsSummary404WhenNoData() throws Exception {
        when(logQueryService.getLatestMetricSnapshot("svc")).thenReturn(null);
        mvc.perform(get("/api/v1/metrics/summary").param("service", "svc"))
                .andExpect(status().isNotFound());
    }
}
