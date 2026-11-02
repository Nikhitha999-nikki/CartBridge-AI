package com.cartbridge.api.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class CheckoutSession {
    private final UUID id;
    private final String productId;
    private final String productName;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal total;
    private final String currency;
    private final Instant createdAt;
    private CheckoutStatus status;
    private String paypalOrderId;
    private String paypalCaptureId;

    public CheckoutSession(
            UUID id,
            String productId,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal total,
            String currency,
            CheckoutStatus status,
            Instant createdAt
    ) {
        this.id = id;
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.total = total;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID id() { return id; }
    public String productId() { return productId; }
    public String productName() { return productName; }
    public int quantity() { return quantity; }
    public BigDecimal unitPrice() { return unitPrice; }
    public BigDecimal total() { return total; }
    public String currency() { return currency; }
    public Instant createdAt() { return createdAt; }
    public synchronized CheckoutStatus status() { return status; }
    public synchronized String paypalOrderId() { return paypalOrderId; }
    public synchronized String paypalCaptureId() { return paypalCaptureId; }

    public synchronized void attachPaypalOrder(String orderId) {
        if (paypalOrderId == null) {
            paypalOrderId = orderId;
        }
    }

    public synchronized void setStatus(CheckoutStatus status) {
        this.status = status;
    }

    public synchronized void complete(String captureId) {
        paypalCaptureId = captureId;
        status = CheckoutStatus.COMPLETED;
    }
}
