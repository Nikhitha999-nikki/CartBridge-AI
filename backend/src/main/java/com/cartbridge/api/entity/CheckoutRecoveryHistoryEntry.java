package com.cartbridge.api.entity;

import java.time.Instant;
import java.util.UUID;

public record CheckoutRecoveryHistoryEntry(
        UUID checkoutSessionId,
        String problemDescription,
        String detectedIssue,
        String explanation,
        String recommendedNextAction,
        String safetyWarning,
        String adviceSource,
        Instant timestamp,
        CheckoutStatus paymentStatus
) {
}
