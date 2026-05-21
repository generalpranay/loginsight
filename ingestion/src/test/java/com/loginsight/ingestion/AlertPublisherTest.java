package com.loginsight.ingestion;

import com.loginsight.common.AlertEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class AlertPublisherTest {

    private static final String TOPIC = "anomaly-alerts";

    private KafkaProducer<String, String> mockProducer;
    private AlertPublisher publisher;

    @BeforeEach
    void setUp() {
        mockProducer = mock(KafkaProducer.class);
        publisher    = new AlertPublisher(mockProducer, TOPIC);
    }

    @Test
    void publish_usesServiceNameAsPartitionKey() {
        publisher.publish(alert("alert-1", "billing"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any());
        assertEquals("billing", captor.getValue().key());
    }

    @Test
    void publish_sendsToConfiguredTopic() {
        publisher.publish(alert("a1", "payments"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any());
        assertEquals(TOPIC, captor.getValue().topic());
    }

    @Test
    void publish_valueIsJsonContainingAlertIdAndService() {
        publisher.publish(alert("alert-99", "auth"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any());
        String json = captor.getValue().value();
        assertNotNull(json);
        assertTrue(json.contains("alert-99"), "JSON should contain alertId");
        assertTrue(json.contains("auth"),     "JSON should contain service");
    }

    @Test
    void publish_doesNotThrowWhenProducerSendFails() {
        doThrow(new RuntimeException("broker unavailable")).when(mockProducer).send(any(), any());
        assertDoesNotThrow(() -> publisher.publish(alert("a2", "svc")));
    }

    @Test
    void publish_callsSendExactlyOnce() {
        publisher.publish(alert("a3", "svc"));
        verify(mockProducer, times(1)).send(any(), any());
    }

    // — helper —

    private static AlertEvent alert(String id, String service) {
        return new AlertEvent(id, service, 500, 2.0, 15.0, 650.0,
                Instant.now(), AlertEvent.Severity.WARNING);
    }
}
