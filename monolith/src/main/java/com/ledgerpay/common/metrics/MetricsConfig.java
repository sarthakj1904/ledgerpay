package com.ledgerpay.common.metrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ledgerpay.common.outbox.OutboxRepository;
import com.ledgerpay.common.outbox.OutboxStatus;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class MetricsConfig {

    @Bean
    public Gauge outboxPendingGauge(MeterRegistry registry, OutboxRepository outboxRepository) {
        return Gauge.builder("ledgerpay.outbox.pending",
                        () -> outboxRepository.countByStatus(OutboxStatus.PENDING))
                .description("Number of pending events in the outbox awaiting publish")
                .register(registry);
    }
}
