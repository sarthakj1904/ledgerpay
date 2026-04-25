package com.ledgerpay.wallet.api;

import jakarta.validation.constraints.Pattern;

public record CreateWalletRequest(@Pattern(regexp = "[A-Z]{3}") String currency) {
    public String currencyOrDefault() {
        return (currency == null || currency.isBlank()) ? "INR" : currency;
    }
}
