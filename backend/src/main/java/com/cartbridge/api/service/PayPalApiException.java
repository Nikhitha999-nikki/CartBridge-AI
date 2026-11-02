package com.cartbridge.api.service;

public class PayPalApiException extends RuntimeException {
    private final int httpStatus;

    public PayPalApiException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
