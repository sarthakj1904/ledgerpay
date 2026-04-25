package com.ledgerpay.payment.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MerchantPayRequest(
        @NotNull UUID fromWalletId,
        @NotNull UUID merchantId,
        @Positive long amountMinor
) {}
