package com.ledgerpay.notification.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.notification.webhook.WebhookClient;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Consumes payment events and delivers mock email notifications + merchant webhooks.
 * Failed events after retries are published to {@code payment.events.DLQ}.
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private static final String DLQ_SUFFIX = ".DLQ";

    private final ObjectMapper mapper;
    private final WebhookClient webhookClient;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry registry;

    public PaymentEventConsumer(ObjectMapper mapper,
                                WebhookClient webhookClient,
                                KafkaTemplate<String, String> kafka,
                                MeterRegistry registry) {
        this.mapper = mapper;
        this.webhookClient = webhookClient;
        this.kafka = kafka;
        this.registry = registry;
    }

    @KafkaListener(
            id = "notification-payments",
            topics = "${ledgerpay.topics.payment-events}",
            groupId = "ledgerpay-notification",
            containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentEvent(String raw, Acknowledgment ack) {
        try {
            JsonNode env = mapper.readTree(raw);
            String eventType = env.path("eventType").asText();
            registry.counter("ledgerpay.notification.received", "event_type", eventType).increment();

            switch (eventType) {
                case "PAYMENT_SUCCESS", "REFUND_INITIATED" -> sendNotifications(env);
                case "PAYMENT_FAILED" -> log.info("Payment FAILED (mock email): {}", env.path("payload"));
                case "PAYMENT_CREATED" -> log.debug("Payment created (no notification yet)");
                default -> log.debug("Ignoring event type {}", eventType);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Notification processing failed; routing to DLQ", e);
            try {
                kafka.send(topicFor(raw) + DLQ_SUFFIX, raw);
            } catch (Exception dlqEx) {
                log.error("Failed to route to DLQ", dlqEx);
            }
            ack.acknowledge();
        }
    }

    private void sendNotifications(JsonNode env) {
        JsonNode payload = env.path("payload");
        log.info("[MOCK-EMAIL] eventId={} txn={} type={} amount={} {}",
                env.path("eventId").asText(),
                payload.path("transactionId").asText(),
                payload.path("type").asText(),
                payload.path("amountMinor").asLong(),
                payload.path("currency").asText());

        String webhookUrl = payload.path("merchantWebhookUrl").asText(null);
        String webhookSecret = payload.path("merchantWebhookSecret").asText(null);
        if (webhookUrl != null && !webhookUrl.isBlank() && !"null".equals(webhookUrl)) {
            log.info("[WEBHOOK] POST {} for txn {}", webhookUrl,
                    payload.path("transactionId").asText());
            webhookClient.send(webhookUrl, webhookSecret, env.toString());
        }
    }

    private String topicFor(String raw) {
        try {
            return mapper.readTree(raw).path("aggregateType").asText("payment.events");
        } catch (Exception e) {
            return "payment.events";
        }
    }
}
