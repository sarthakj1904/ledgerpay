package com.ledgerpay.fraud.consumer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerpay.fraud.rule.FraudRule;
import com.ledgerpay.fraud.rule.FraudRuleResult;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Consumes payment events, evaluates every registered {@link FraudRule}, and publishes a
 * {@code FRAUD_ALERT} to {@code fraud.events} when a rule fires. Rules share the same envelope
 * schema used by the monolith so round-trip consumption works without translation.
 */
@Component
public class FraudEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudEventConsumer.class);

    private final List<FraudRule> rules;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry registry;
    private final String fraudTopic;

    public FraudEventConsumer(List<FraudRule> rules,
                              ObjectMapper mapper,
                              KafkaTemplate<String, String> kafka,
                              MeterRegistry registry,
                              @Value("${ledgerpay.topics.fraud-events}") String fraudTopic) {
        this.rules = rules;
        this.mapper = mapper;
        this.kafka = kafka;
        this.registry = registry;
        this.fraudTopic = fraudTopic;
    }

    @KafkaListener(
            id = "fraud-rule-eval",
            topics = "${ledgerpay.topics.payment-events}",
            groupId = "ledgerpay-fraud",
            containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentEvent(String raw, Acknowledgment ack) {
        try {
            JsonNode env = mapper.readTree(raw);
            String eventType = env.path("eventType").asText();
            JsonNode payload = env.path("payload").deepCopy();
            ((ObjectNode) payload).put("_eventType", eventType);

            for (FraudRule rule : rules) {
                try {
                    FraudRuleResult result = rule.evaluate(payload);
                    if (result != null) {
                        emitAlert(payload.path("transactionId").asText(), result);
                    }
                } catch (Exception ruleEx) {
                    log.error("Rule {} blew up on event {}", rule.getClass().getSimpleName(),
                            env.path("eventId").asText(), ruleEx);
                }
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to parse payment event; skipping: {}", raw, e);
            ack.acknowledge();
        }
    }

    private void emitAlert(String transactionId, FraudRuleResult result) throws Exception {
        registry.counter("ledgerpay.fraud.triggered", "rule", result.ruleName()).increment();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("transactionId", transactionId);
        payload.put("ruleName", result.ruleName());
        payload.put("severity", result.severity());
        payload.set("details", mapper.valueToTree(result.details()));

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "FRAUD_ALERT");
        envelope.put("aggregateType", "transaction");
        envelope.put("aggregateId", transactionId);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("version", 1);
        envelope.set("payload", payload);

        kafka.send(fraudTopic, transactionId, mapper.writeValueAsString(envelope));
        log.info("Emitted FRAUD_ALERT for txn {} rule={} severity={}",
                transactionId, result.ruleName(), result.severity());
    }

    // Suppress unused-field warning for Map in logs during development.
    @SuppressWarnings("unused")
    private static Map<String, Object> empty() { return Map.of(); }
}
