package com.cartbridge.api.service;

public class CheckoutRecoveryTimeoutException extends RuntimeException {
    public CheckoutRecoveryTimeoutException(Throwable cause) {
        super("Gemini request exceeded the configured time limit.", cause);
    }
}
