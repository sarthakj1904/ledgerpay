package com.ledgerpay.common.outbox;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.common.event.Topics;

/**
 * Scheduled worker that drains the transactional outbox to Kafka.
 * <p>
 * The batch of {@link OutboxStatus#PENDING} rows is locked with {@code FOR UPDATE SKIP LOCKED}
 * inside a single DB transaction. Successful publishes mark rows {@code PUBLISHED}, failures
 * increment {@code retryCount} and after {@code maxRetries} the event is routed to the DLQ topic
 * and marked {@link OutboxStatus#FAILED}. Lock release + status flip are atomic.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_MS = 5_000L;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final Topics topics;
    private final int batchSize;
    private final int maxRetries;

    public OutboxPublisher(OutboxRepository repository,
                           KafkaTemplate<String, String> kafka,
                           Topics topics,
                           @Value("${ledgerpay.outbox.poll-batch-size:100}") int batchSize,
                           @Value("${ledgerpay.outbox.max-retries:5}") int maxRetries) {
        this.repository = repository;
        this.kafka = kafka;
        this.topics = topics;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
    }

    @Scheduled(fixedDelayString = "${ledgerpay.outbox.poll-delay-ms:500}")
    @Transactional
    public void publishBatch() {
        List<OutboxEvent> batch = repository.lockPendingBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Outbox publishing batch of {} events", batch.size());
        for (OutboxEvent e : batch) {
            try {
                publishOne(e);
                e.setStatus(OutboxStatus.PUBLISHED);
                e.setPublishedAt(Instant.now());
                e.setLastError(null);
            } catch (Exception ex) {
                e.setRetryCount(e.getRetryCount() + 1);
                e.setLastError(truncate(ex.getMessage()));
                if (e.getRetryCount() >= maxRetries) {
                    log.error("Outbox event {} exceeded max retries; routing to DLQ", e.getId(), ex);
                    routeToDlq(e);
                    e.setStatus(OutboxStatus.FAILED);
                    e.setPublishedAt(Instant.now());
                } else {
                    log.warn("Outbox event {} publish failed (attempt {}): {}",
                            e.getId(), e.getRetryCount(), ex.getMessage());
                }
            }
        }
    }

    private void publishOne(OutboxEvent e) throws InterruptedException, ExecutionException, TimeoutException {
        SendResult<String, String> result = kafka
                .send(e.getTopic(), e.getAggregateId(), e.getPayload())
                .get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        log.debug("Published outbox {} to {} partition={} offset={}",
                e.getId(),
                e.getTopic(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
    }

    private void routeToDlq(OutboxEvent e) {
        try {
            kafka.send(topics.dlq(e.getTopic()), e.getAggregateId(), e.getPayload())
                    .get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception dlqEx) {
            log.error("Failed to route event {} to DLQ; will remain FAILED", e.getId(), dlqEx);
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
