package com.cartbridge.api.service;

public class InvalidCheckoutRequestException extends RuntimeException {
    public InvalidCheckoutRequestException(String message) {
        super(message);
    }
}
