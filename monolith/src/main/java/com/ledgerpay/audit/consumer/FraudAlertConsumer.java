package com.ledgerpay.audit.consumer;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.audit.domain.FraudFlag;
import com.ledgerpay.audit.domain.FraudFlagRepository;
import com.ledgerpay.audit.domain.FraudSeverity;
import com.ledgerpay.ledger.domain.PaymentTransaction;
import com.ledgerpay.ledger.domain.PaymentTransactionRepository;
import com.ledgerpay.ledger.domain.TransactionStatus;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Consumes FRAUD_ALERT events emitted by the fraud service and flips the associated transaction
 * to {@link TransactionStatus#FLAGGED}, inserting a {@link FraudFlag} record for audit.
 */
@Component
public class FraudAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudAlertConsumer.class);

    private final PaymentTransactionRepository transactionRepository;
    private final FraudFlagRepository fraudFlagRepository;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;

    public FraudAlertConsumer(PaymentTransactionRepository transactionRepository,
                              FraudFlagRepository fraudFlagRepository,
                              ObjectMapper mapper,
                              MeterRegistry registry) {
        this.transactionRepository = transactionRepository;
        this.fraudFlagRepository = fraudFlagRepository;
        this.mapper = mapper;
        this.registry = registry;
    }

    @KafkaListener(
            id = "fraud-alerts",
            topics = "${ledgerpay.topics.fraud-events}",
            groupId = "ledgerpay-fraud-alerts",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onFraudAlert(String raw, Acknowledgment ack) {
        try {
            JsonNode env = mapper.readTree(raw);
            if (!"FRAUD_ALERT".equals(env.path("eventType").asText())) {
                ack.acknowledge();
                return;
            }
            JsonNode payload = env.path("payload");
            UUID txnId = UUID.fromString(payload.path("transactionId").asText());
            String rule = payload.path("ruleName").asText("UNKNOWN");
            FraudSeverity severity = parseSeverity(payload.path("severity").asText("MEDIUM"));

            Optional<PaymentTransaction> maybeTxn = transactionRepository.findById(txnId);
            if (maybeTxn.isEmpty()) {
                log.warn("FRAUD_ALERT references missing txn {}; skipping", txnId);
                ack.acknowledge();
                return;
            }
            PaymentTransaction t = maybeTxn.get();
            if (t.getStatus() != TransactionStatus.FLAGGED) {
                t.setStatus(TransactionStatus.FLAGGED);
                transactionRepository.save(t);
            }
            fraudFlagRepository.save(FraudFlag.create(txnId, rule, severity,
                    mapper.writeValueAsString(payload)));
            registry.counter("ledgerpay.fraud.flags.total", "rule", rule).increment();

            log.info("Flagged txn {} via rule {} (severity {})", txnId, rule, severity);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process FRAUD_ALERT event: {}", raw, e);
            throw new RuntimeException(e);
        }
    }

    private static FraudSeverity parseSeverity(String s) {
        try { return FraudSeverity.valueOf(s.toUpperCase()); }
        catch (Exception e) { return FraudSeverity.MEDIUM; }
    }
}
