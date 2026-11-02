package com.cartbridge.api.dto;

import com.cartbridge.api.entity.CheckoutSession;
import com.cartbridge.api.entity.CheckoutStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CheckoutSessionResponse(
        UUID id,
        String productId,
        String productName,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal total,
        String currency,
        CheckoutStatus status,
        Instant createdAt,
        String paypalOrderId,
        String paypalCaptureId
) {
    public static CheckoutSessionResponse from(CheckoutSession session) {
        return new CheckoutSessionResponse(
                session.id(),
                session.productId(),
                session.productName(),
                session.quantity(),
                session.unitPrice(),
                session.total(),
                session.currency(),
                session.status(),
                session.createdAt(),
                session.paypalOrderId(),
                session.paypalCaptureId()
        );
    }
}
