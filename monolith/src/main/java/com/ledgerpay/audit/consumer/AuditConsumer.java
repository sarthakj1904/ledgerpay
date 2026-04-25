package com.ledgerpay.audit.consumer;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.audit.domain.AuditLog;
import com.ledgerpay.audit.domain.AuditLogRepository;

/**
 * Mirrors every payment and fraud event into {@code audit_logs} for compliance and traceability.
 */
@Component
public class AuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditConsumer.class);

    private final AuditLogRepository repository;
    private final ObjectMapper mapper;

    public AuditConsumer(AuditLogRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @KafkaListener(
            id = "audit-payments",
            topics = "${ledgerpay.topics.payment-events}",
            groupId = "ledgerpay-audit",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void consumePayments(String raw, Acknowledgment ack) {
        persist(raw);
        ack.acknowledge();
    }

    @KafkaListener(
            id = "audit-fraud",
            topics = "${ledgerpay.topics.fraud-events}",
            groupId = "ledgerpay-audit",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void consumeFraud(String raw, Acknowledgment ack) {
        persist(raw);
        ack.acknowledge();
    }

    private void persist(String raw) {
        try {
            JsonNode env = mapper.readTree(raw);
            String eventType = env.path("eventType").asText();
            String aggregateType = env.path("aggregateType").asText("unknown");
            String aggregateId = env.path("aggregateId").asText();
            JsonNode payload = env.path("payload");
            UUID actorId = optUuid(payload.path("initiatorUserId").asText(null));
            String ip = payload.path("clientIp").asText(null);
            repository.save(AuditLog.of(actorId, eventType, aggregateType, aggregateId,
                    mapper.writeValueAsString(payload), ip));
        } catch (Exception e) {
            log.error("Failed to audit event: {}", raw, e);
            throw new RuntimeException(e);
        }
    }

    private static UUID optUuid(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
