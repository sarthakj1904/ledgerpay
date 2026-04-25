package com.ledgerpay.common.outbox;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import com.ledgerpay.common.event.EventEnvelope;
import com.ledgerpay.common.exception.DomainException;

/**
 * Writes outbox rows. Must be called from within an existing {@code @Transactional} business
 * method so that the business state + outbox row commit together.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void write(String topic,
                      String aggregateType,
                      String aggregateId,
                      String eventType,
                      Object payload) {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                eventType,
                aggregateType,
                aggregateId,
                Instant.now(),
                1,
                objectMapper.valueToTree(payload)
        );
        ObjectNode node = objectMapper.valueToTree(envelope);
        try {
            String json = objectMapper.writeValueAsString(node);
            repository.save(OutboxEvent.newPending(aggregateType, aggregateId, eventType, topic, json));
        } catch (JsonProcessingException e) {
            throw new OutboxSerializationException(e);
        }
    }

    private static final class OutboxSerializationException extends DomainException {
        OutboxSerializationException(Throwable cause) {
            super(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "outbox_serialization_failed", cause.getMessage());
        }
    }
}
