package com.ledgerpay.fraud.rule;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerpay.fraud.config.FraudProperties;

@Component
public class IpBlocklistRule implements FraudRule {

    private static final String RULE = "IP_BLOCKLIST";

    private final Set<String> blocklist;

    public IpBlocklistRule(FraudProperties props) {
        this.blocklist = new HashSet<>(props.ipBlocklist() == null ? Set.of() : props.ipBlocklist());
    }

    @Override
    public FraudRuleResult evaluate(JsonNode payload) {
        String eventType = payload.path("_eventType").asText();
        if (!"PAYMENT_CREATED".equals(eventType)) return null;
        String ip = payload.path("clientIp").asText(null);
        if (ip == null || !blocklist.contains(ip)) return null;
        return FraudRuleResult.high(RULE, Map.of("clientIp", ip));
    }
}
