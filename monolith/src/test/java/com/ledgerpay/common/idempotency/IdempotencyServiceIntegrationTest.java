package com.ledgerpay.common.idempotency;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.common.exception.ConflictException;
import com.ledgerpay.common.idempotency.IdempotencyService.IdempotencyResult;
import com.ledgerpay.testsupport.IntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceIntegrationTest extends IntegrationTestBase {

    @Autowired IdempotencyService service;
    @Autowired ObjectMapper mapper;

    @Test
    void firstCallIsFreshAndReplayReturnsCachedResponse() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();
        String body = "{\"a\":1}";

        IdempotencyResult first = service.beginOrReplay(key, userId, "POST", "/api/v1/payments/transfer", body);
        assertThat(first.replay()).isFalse();

        service.complete(key, 201, "{\"id\":\"abc\"}");

        IdempotencyResult replay = service.beginOrReplay(key, userId, "POST", "/api/v1/payments/transfer", body);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.status()).isEqualTo(201);
        // Postgres jsonb normalizes whitespace on round-trip; compare JSON trees, not strings.
        assertThat(mapper.readTree(replay.body()))
                .isEqualTo(mapper.readTree("{\"id\":\"abc\"}"));
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        UUID userId = UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();

        service.beginOrReplay(key, userId, "POST", "/api/v1/payments/transfer", "{\"a\":1}");
        service.complete(key, 201, "{}");

        assertThatThrownBy(() -> service.beginOrReplay(key, userId, "POST",
                "/api/v1/payments/transfer", "{\"a\":2}"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different request payload");
    }
}
