package com.loginsight.ingestion;

import com.loginsight.common.LogEntry;
import com.loginsight.telemetry.TelemetryConfig;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class KafkaLogConsumerTest {

    private static final String TOPIC = "logs";

    private KafkaConsumer<String, String> mockKafka;
    private TelemetryConfig mockTelemetry;

    @BeforeEach
    void setUp() {
        mockKafka     = mock(KafkaConsumer.class);
        mockTelemetry = mock(TelemetryConfig.class);
        when(mockKafka.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
    }

    @Test
    void validRecordIsDeserializedAndForwardedDownstream() throws Exception {
        String json = validEntryJson("id-1", "payments");
        ConsumerRecords<String, String> batch = singleRecord(TOPIC, 0, 5L, json);

        CountDownLatch latch    = new CountDownLatch(1);
        List<LogEntry> captured = Collections.synchronizedList(new ArrayList<>());

        when(mockKafka.poll(any(Duration.class)))
                .thenReturn(batch)
                .thenReturn(ConsumerRecords.empty());

        try (KafkaLogConsumer consumer = new KafkaLogConsumer(
                mockKafka, entries -> { captured.addAll(entries); latch.countDown(); },
                mockTelemetry, TOPIC)) {
            consumer.startAsync();
            assertTrue(latch.await(2, TimeUnit.SECONDS), "downstream not called within 2 s");
        }

        assertEquals(1, captured.size());
        assertEquals("id-1",     captured.get(0).id());
        assertEquals("payments", captured.get(0).service());
    }

    @Test
    void eosCommitSentAfterDownstreamWithCorrectOffset() throws Exception {
        ConsumerRecords<String, String> batch = singleRecord(TOPIC, 0, 10L, validEntryJson("e1", "svc"));

        CountDownLatch latch = new CountDownLatch(1);
        when(mockKafka.poll(any(Duration.class)))
                .thenReturn(batch)
                .thenReturn(ConsumerRecords.empty());

        try (KafkaLogConsumer consumer = new KafkaLogConsumer(
                mockKafka, entries -> latch.countDown(), mockTelemetry, TOPIC)) {
            consumer.startAsync();
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        }

        // EOS guarantee: commitSync is called with offset + 1 = 11
        verify(mockKafka, atLeastOnce()).commitSync(
                argThat((Map<TopicPartition, OffsetAndMetadata> m) -> {
                    OffsetAndMetadata om = m.get(new TopicPartition(TOPIC, 0));
                    return om != null && om.offset() == 11L;
                }),
                any(Duration.class)
        );
    }

    @Test
    void deserializationErrorSkipsBadRecordAndProcessesGoodOnes() throws Exception {
        ConsumerRecord<String, String> bad  = new ConsumerRecord<>(TOPIC, 0, 1L, null, "not-json{{{");
        ConsumerRecord<String, String> good = new ConsumerRecord<>(TOPIC, 0, 2L, null, validEntryJson("good-1", "svc"));
        Map<TopicPartition, List<ConsumerRecord<String, String>>> map = new HashMap<>();
        map.put(new TopicPartition(TOPIC, 0), List.of(bad, good));
        ConsumerRecords<String, String> mixed = new ConsumerRecords<>(map);

        CountDownLatch latch    = new CountDownLatch(1);
        List<LogEntry> captured = Collections.synchronizedList(new ArrayList<>());

        when(mockKafka.poll(any(Duration.class)))
                .thenReturn(mixed)
                .thenReturn(ConsumerRecords.empty());

        try (KafkaLogConsumer consumer = new KafkaLogConsumer(
                mockKafka, entries -> { captured.addAll(entries); latch.countDown(); },
                mockTelemetry, TOPIC)) {
            consumer.startAsync();
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        }

        assertEquals(1, captured.size(), "only the valid record should reach downstream");
        assertEquals("good-1", captured.get(0).id());
    }

    @Test
    void allBadRecordsProduceNoBatchToDownstream() throws Exception {
        ConsumerRecord<String, String> bad1 = new ConsumerRecord<>(TOPIC, 0, 1L, null, "bad1");
        ConsumerRecord<String, String> bad2 = new ConsumerRecord<>(TOPIC, 0, 2L, null, "bad2");
        Map<TopicPartition, List<ConsumerRecord<String, String>>> map = new HashMap<>();
        map.put(new TopicPartition(TOPIC, 0), List.of(bad1, bad2));
        ConsumerRecords<String, String> allBad = new ConsumerRecords<>(map);

        List<List<LogEntry>> downstreamCalls = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch pollDone = new CountDownLatch(1);

        when(mockKafka.poll(any(Duration.class)))
                .thenAnswer(inv -> { pollDone.countDown(); return allBad; })
                .thenReturn(ConsumerRecords.empty());

        try (KafkaLogConsumer consumer = new KafkaLogConsumer(
                mockKafka, downstreamCalls::add, mockTelemetry, TOPIC)) {
            consumer.startAsync();
            pollDone.await(2, TimeUnit.SECONDS);
            Thread.sleep(50); // let poll loop process
        }

        assertTrue(downstreamCalls.isEmpty(), "downstream must not be called when all records fail deserialization");
    }

    @Test
    void rebalanceListenerCommitsBeforePartitionsRevoked() throws Exception {
        AtomicReference<ConsumerRebalanceListener> listenerRef = new AtomicReference<>();
        CountDownLatch subscribeLatch = new CountDownLatch(1);

        doAnswer(inv -> {
            listenerRef.set(inv.getArgument(1));
            subscribeLatch.countDown();
            return null;
        }).when(mockKafka).subscribe(anyList(), any(ConsumerRebalanceListener.class));

        KafkaLogConsumer consumer = new KafkaLogConsumer(
                mockKafka, entries -> {}, mockTelemetry, TOPIC);
        consumer.startAsync();

        assertTrue(subscribeLatch.await(2, TimeUnit.SECONDS), "subscribe() not called within 2 s");

        ConsumerRebalanceListener listener = listenerRef.get();
        assertNotNull(listener);
        listener.onPartitionsRevoked(List.of(new TopicPartition(TOPIC, 0)));

        consumer.close();

        verify(mockKafka, atLeastOnce()).commitSync(any(Duration.class));
    }

    // — helpers —

    private static ConsumerRecords<String, String> singleRecord(
            String topic, int partition, long offset, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, partition, offset, null, value);
        Map<TopicPartition, List<ConsumerRecord<String, String>>> map = new HashMap<>();
        map.put(new TopicPartition(topic, partition), List.of(record));
        return new ConsumerRecords<>(map);
    }

    private static String validEntryJson(String id, String service) {
        return """
                {"id":"%s","service":"%s","level":"INFO","statusCode":200,\
                "message":"ok","host":"host","traceId":"t",\
                "timestamp":"2024-01-01T00:00:00Z","tags":{}}""".formatted(id, service);
    }
}
