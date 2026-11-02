package com.cartbridge.api.service;

import com.cartbridge.api.dto.CheckoutRecoveryAdviceResponse;

import java.math.BigDecimal;
import java.time.Instant;

public interface CheckoutRecoveryAdvisor {
    CheckoutRecoveryAdviceResponse generateAdvice(String problemDescription, CheckoutContext context);

    record CheckoutContext(
            String status,
            String productName,
            int quantity,
            BigDecimal total,
            String currency,
            Instant createdAt,
            boolean paypalOrderCreated,
            boolean captureReferencePresent
    ) {
    }
}
