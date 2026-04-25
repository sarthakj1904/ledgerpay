package com.ledgerpay.common.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgerpay.topics")
public record Topics(
        String paymentEvents,
        String walletEvents,
        String fraudEvents
) {
    public String dlq(String topic) {
        return topic + ".DLQ";
    }
}
