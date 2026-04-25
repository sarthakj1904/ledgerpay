package com.ledgerpay.fraud.rule;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerpay.fraud.config.FraudProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class VelocityRuleTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void flagsWhenCountExceedsThreshold() {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(6L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        FraudProperties props = new FraudProperties(
                new FraudProperties.Velocity(60, 5),
                new FraudProperties.AmountSpike(10, 10_000),
                new FraudProperties.FailureBurst(300, 3),
                null
        );
        VelocityRule rule = new VelocityRule(redis, props);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("_eventType", "PAYMENT_CREATED");
        payload.put("sourceWalletId", UUID.randomUUID().toString());

        FraudRuleResult result = rule.evaluate(payload);
        assertThat(result).isNotNull();
        assertThat(result.ruleName()).isEqualTo("VELOCITY");
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void doesNotFlagBelowThreshold() {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(3L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        FraudProperties props = new FraudProperties(
                new FraudProperties.Velocity(60, 5),
                new FraudProperties.AmountSpike(10, 10_000),
                new FraudProperties.FailureBurst(300, 3),
                null
        );
        VelocityRule rule = new VelocityRule(redis, props);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("_eventType", "PAYMENT_CREATED");
        payload.put("sourceWalletId", UUID.randomUUID().toString());

        assertThat(rule.evaluate(payload)).isNull();
    }

    @Test
    void ignoresNonPaymentCreatedEvents() {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        FraudProperties props = new FraudProperties(
                new FraudProperties.Velocity(60, 5),
                new FraudProperties.AmountSpike(10, 10_000),
                new FraudProperties.FailureBurst(300, 3),
                null
        );
        VelocityRule rule = new VelocityRule(redis, props);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("_eventType", "PAYMENT_SUCCESS");

        assertThat(rule.evaluate(payload)).isNull();
        Mockito.verifyNoInteractions(redis);
    }
}
