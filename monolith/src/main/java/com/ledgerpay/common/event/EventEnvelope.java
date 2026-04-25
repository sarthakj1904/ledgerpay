package com.ledgerpay.common.event;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Canonical event envelope published to Kafka. `payload` is a tree node so consumers can decode
 * without sharing DTO classes.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        int version,
        JsonNode payload
) {}
