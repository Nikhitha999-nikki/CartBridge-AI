package com.cartbridge.api.service;

public class PaymentOperationException extends RuntimeException {
    public PaymentOperationException(String message) {
        super(message);
    }
}
