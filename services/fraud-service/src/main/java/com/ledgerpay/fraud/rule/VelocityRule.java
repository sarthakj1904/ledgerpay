package com.ledgerpay.fraud.rule;

import java.time.Duration;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerpay.fraud.config.FraudProperties;

/**
 * Rule R1: flags when a single source wallet has more than {@code threshold} payments in the
 * rolling time window. Implemented with a Redis INCR + EXPIRE per window.
 */
@Component
public class VelocityRule implements FraudRule {

    private static final String RULE = "VELOCITY";

    private final StringRedisTemplate redis;
    private final FraudProperties props;

    public VelocityRule(StringRedisTemplate redis, FraudProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public FraudRuleResult evaluate(JsonNode payload) {
        String eventType = payload.path("_eventType").asText();
        if (!"PAYMENT_CREATED".equals(eventType)) return null;
        String source = payload.path("sourceWalletId").asText(null);
        if (source == null || "null".equals(source)) return null;

        int win = props.velocity().windowSeconds();
        String key = "fraud:velocity:" + source + ":" + (System.currentTimeMillis() / 1000 / win);
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofSeconds(win * 2L));
        if (count != null && count > props.velocity().threshold()) {
            return FraudRuleResult.high(RULE, Map.of(
                    "windowSeconds", win,
                    "count", count,
                    "threshold", props.velocity().threshold(),
                    "sourceWalletId", source
            ));
        }
        return null;
    }
}
