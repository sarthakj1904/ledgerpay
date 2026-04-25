package com.ledgerpay.fraud.rule;

import com.fasterxml.jackson.databind.JsonNode;

public interface FraudRule {
    /**
     * @param payload the PaymentEventPayload JSON node.
     * @return a {@link FraudRuleResult} describing a trigger, or {@code null} if the rule passes.
     */
    FraudRuleResult evaluate(JsonNode payload);
}
