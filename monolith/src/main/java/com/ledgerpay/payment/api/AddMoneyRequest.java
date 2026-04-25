package com.ledgerpay.payment.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddMoneyRequest(
        @NotNull UUID walletId,
        @Positive long amountMinor,
        String currency
) {
    public String currencyOrDefault() {
        return (currency == null || currency.isBlank()) ? "INR" : currency;
    }
}
