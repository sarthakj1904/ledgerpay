package com.ledgerpay.payment.api;

import java.time.Instant;
import java.util.UUID;

import com.ledgerpay.ledger.domain.PaymentTransaction;
import com.ledgerpay.ledger.domain.TransactionStatus;
import com.ledgerpay.ledger.domain.TransactionType;

public record TransactionResponse(
        UUID id,
        String idempotencyKey,
        TransactionType type,
        TransactionStatus status,
        UUID sourceWalletId,
        UUID destWalletId,
        long amountMinor,
        String currency,
        UUID parentTransactionId,
        int riskScore,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static TransactionResponse from(PaymentTransaction t) {
        return new TransactionResponse(
                t.getId(), t.getIdempotencyKey(), t.getType(), t.getStatus(),
                t.getSourceWalletId(), t.getDestWalletId(),
                t.getAmountMinor(), t.getCurrency(),
                t.getParentTransactionId(), t.getRiskScore(), t.getFailureReason(),
                t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
