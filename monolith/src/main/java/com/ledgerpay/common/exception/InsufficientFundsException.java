package com.ledgerpay.common.exception;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends DomainException {
    public InsufficientFundsException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient_funds", message);
    }
}
