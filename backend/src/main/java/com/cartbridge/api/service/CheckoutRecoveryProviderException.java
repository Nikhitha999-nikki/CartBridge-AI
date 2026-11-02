package com.cartbridge.api.service;

public class CheckoutRecoveryProviderException extends RuntimeException {
    public CheckoutRecoveryProviderException() {
        super("Checkout recovery advice is temporarily unavailable. Please try again later.");
    }

    public CheckoutRecoveryProviderException(Throwable cause) {
        super("Checkout recovery advice is temporarily unavailable. Please try again later.", cause);
    }
}
