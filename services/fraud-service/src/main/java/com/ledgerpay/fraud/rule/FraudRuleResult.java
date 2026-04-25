package com.ledgerpay.fraud.rule;

import java.util.Map;

public record FraudRuleResult(String ruleName, String severity, Map<String, Object> details) {
    public static FraudRuleResult pass() { return null; }
    public static FraudRuleResult high(String name, Map<String, Object> details) {
        return new FraudRuleResult(name, "HIGH", details);
    }
    public static FraudRuleResult medium(String name, Map<String, Object> details) {
        return new FraudRuleResult(name, "MEDIUM", details);
    }
}
