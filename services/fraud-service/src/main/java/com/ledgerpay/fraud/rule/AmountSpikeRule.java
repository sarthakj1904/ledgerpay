package com.ledgerpay.fraud.rule;

import java.time.Duration;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerpay.fraud.config.FraudProperties;

/**
 * Rule R2: flags when a single transaction exceeds {@code multiplier} times the wallet's rolling
 * 30-day average. The rolling sum and count are kept in Redis keyed by day bucket.
 */
@Component
public class AmountSpikeRule implements FraudRule {

    private static final String RULE = "AMOUNT_SPIKE";
    private static final int WINDOW_DAYS = 30;

    private final StringRedisTemplate redis;
    private final FraudProperties props;

    public AmountSpikeRule(StringRedisTemplate redis, FraudProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public FraudRuleResult evaluate(JsonNode payload) {
        String eventType = payload.path("_eventType").asText();
        if (!"PAYMENT_CREATED".equals(eventType)) return null;
        String source = payload.path("sourceWalletId").asText(null);
        if (source == null || "null".equals(source)) return null;

        long amount = payload.path("amountMinor").asLong();
        String sumKey = "fraud:spike:sum:" + source;
        String cntKey = "fraud:spike:count:" + source;

        long sum = longValue(redis.opsForValue().get(sumKey));
        long cnt = longValue(redis.opsForValue().get(cntKey));
        long avg = cnt > 0 ? sum / cnt : 0;

        FraudRuleResult triggered = null;
        if (cnt >= 3 && avg >= props.amountSpike().minBaselineMinor()
                && amount > avg * props.amountSpike().multiplier()) {
            triggered = FraudRuleResult.high(RULE, Map.of(
                    "avgAmountMinor", avg,
                    "txnAmountMinor", amount,
                    "multiplier", props.amountSpike().multiplier(),
                    "sourceWalletId", source
            ));
        }

        redis.opsForValue().increment(sumKey, amount);
        redis.opsForValue().increment(cntKey, 1);
        redis.expire(sumKey, Duration.ofDays(WINDOW_DAYS));
        redis.expire(cntKey, Duration.ofDays(WINDOW_DAYS));

        return triggered;
    }

    private static long longValue(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }
}
