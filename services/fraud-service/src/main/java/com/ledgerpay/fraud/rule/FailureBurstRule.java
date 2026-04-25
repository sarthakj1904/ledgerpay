package com.ledgerpay.fraud.rule;

import java.time.Duration;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerpay.fraud.config.FraudProperties;

/**
 * Rule R3: counts {@code PAYMENT_FAILED} events per source wallet in a rolling window and flags
 * bursts above {@code threshold}.
 */
@Component
public class FailureBurstRule implements FraudRule {

    private static final String RULE = "FAILURE_BURST";

    private final StringRedisTemplate redis;
    private final FraudProperties props;

    public FailureBurstRule(StringRedisTemplate redis, FraudProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public FraudRuleResult evaluate(JsonNode payload) {
        String eventType = payload.path("_eventType").asText();
        if (!"PAYMENT_FAILED".equals(eventType)) return null;
        String source = payload.path("sourceWalletId").asText(null);
        if (source == null || "null".equals(source)) return null;

        int win = props.failureBurst().windowSeconds();
        String key = "fraud:failures:" + source + ":" + (System.currentTimeMillis() / 1000 / win);
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofSeconds(win * 2L));
        if (count != null && count > props.failureBurst().threshold()) {
            return FraudRuleResult.medium(RULE, Map.of(
                    "windowSeconds", win,
                    "count", count,
                    "threshold", props.failureBurst().threshold(),
                    "sourceWalletId", source
            ));
        }
        return null;
    }
}
