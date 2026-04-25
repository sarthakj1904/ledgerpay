package com.ledgerpay.payment.service;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.ledgerpay.ledger.domain.TransactionStatus;
import com.ledgerpay.ledger.domain.TransactionType;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class PaymentMetrics {

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordOutcome(TransactionType type, TransactionStatus status, long latencyNs) {
        registry.counter("ledgerpay.payment.total",
                "type", type.name(), "status", status.name()).increment();
        Timer.builder("ledgerpay.payment.latency")
                .tag("type", type.name())
                .tag("status", status.name())
                .publishPercentileHistogram()
                .register(registry)
                .record(latencyNs, TimeUnit.NANOSECONDS);
    }
}
