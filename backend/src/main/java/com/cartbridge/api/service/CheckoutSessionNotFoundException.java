package com.cartbridge.api.service;

public class CheckoutSessionNotFoundException extends RuntimeException {
    public CheckoutSessionNotFoundException() {
        super("Checkout session not found.");
    }
}
