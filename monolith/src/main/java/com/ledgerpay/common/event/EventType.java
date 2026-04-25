package com.ledgerpay.common.event;

public final class EventType {
    public static final String PAYMENT_CREATED = "PAYMENT_CREATED";
    public static final String PAYMENT_SUCCESS = "PAYMENT_SUCCESS";
    public static final String PAYMENT_FAILED  = "PAYMENT_FAILED";
    public static final String REFUND_INITIATED = "REFUND_INITIATED";
    public static final String WALLET_LOW_BALANCE = "WALLET_LOW_BALANCE";
    public static final String FRAUD_ALERT = "FRAUD_ALERT";

    private EventType() {}
}
