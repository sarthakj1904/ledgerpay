package com.ledgerpay.common.outbox;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.ledgerpay.common.event.Topics;
import com.ledgerpay.testsupport.IntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublisherIntegrationTest extends IntegrationTestBase {

    @Autowired OutboxRepository outboxRepository;
    @Autowired Topics topics;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrap;

    @Test
    void pendingOutboxRowsArePublishedToKafka() {
        OutboxEvent e = OutboxEvent.newPending(
                "test",
                UUID.randomUUID().toString(),
                "TEST_EVENT",
                topics.paymentEvents(),
                "{\"eventType\":\"TEST_EVENT\",\"payload\":{\"hello\":\"world\"}}");
        outboxRepository.save(e);

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(Collections.singletonList(topics.paymentEvents()));
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> r : records) {
                    if (r.value().contains("TEST_EVENT")) { found = true; break; }
                }
                assertThat(found).isTrue();
            });
        }
    }

    private KafkaConsumer<String, String> newConsumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(p);
    }
}
