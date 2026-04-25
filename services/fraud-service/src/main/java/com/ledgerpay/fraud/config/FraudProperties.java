package com.ledgerpay.fraud.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgerpay.fraud")
public record FraudProperties(
        Velocity velocity,
        AmountSpike amountSpike,
        FailureBurst failureBurst,
        List<String> ipBlocklist
) {
    public record Velocity(int windowSeconds, int threshold) {}
    public record AmountSpike(int multiplier, long minBaselineMinor) {}
    public record FailureBurst(int windowSeconds, int threshold) {}
}
