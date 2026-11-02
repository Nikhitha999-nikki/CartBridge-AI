package com.cartbridge.api.dto;

import com.cartbridge.api.entity.CheckoutSession;
import com.cartbridge.api.entity.CheckoutStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentResultResponse(
        UUID checkoutSessionId,
        String paypalOrderId,
        String paypalCaptureId,
        CheckoutStatus status,
        BigDecimal amount,
        String currency,
        String message
) {
    public static PaymentResultResponse from(CheckoutSession session, String message) {
        return new PaymentResultResponse(
                session.id(),
                session.paypalOrderId(),
                session.paypalCaptureId(),
                session.status(),
                session.total(),
                session.currency(),
                message
        );
    }
}
