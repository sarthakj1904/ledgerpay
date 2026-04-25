package com.ledgerpay.payment.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RefundRequest(
        @NotNull UUID transactionId,
        @Positive long amountMinor
) {}
