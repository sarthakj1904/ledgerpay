package com.ledgerpay.payment.event;

import java.util.UUID;

import com.ledgerpay.ledger.domain.PaymentTransaction;

public record PaymentEventPayload(
        UUID transactionId,
        String type,
        String status,
        UUID sourceWalletId,
        UUID destWalletId,
        long amountMinor,
        String currency,
        UUID parentTransactionId,
        UUID initiatorUserId,
        String clientIp,
        String failureReason,
        UUID merchantId,
        String merchantWebhookUrl,
        String merchantWebhookSecret
) {
    public static PaymentEventPayload from(PaymentTransaction t) {
        return from(t, null, null, null);
    }

    public static PaymentEventPayload from(PaymentTransaction t,
                                           UUID merchantId,
                                           String webhookUrl,
                                           String webhookSecret) {
        return new PaymentEventPayload(
                t.getId(),
                t.getType().name(),
                t.getStatus().name(),
                t.getSourceWalletId(),
                t.getDestWalletId(),
                t.getAmountMinor(),
                t.getCurrency(),
                t.getParentTransactionId(),
                t.getInitiatorUserId(),
                t.getClientIp(),
                t.getFailureReason(),
                merchantId,
                webhookUrl,
                webhookSecret
        );
    }
}
