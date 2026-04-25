package com.ledgerpay.common.exception;

import org.springframework.http.HttpStatus;

public class WalletFrozenException extends DomainException {
    public WalletFrozenException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "wallet_frozen", message);
    }
}
