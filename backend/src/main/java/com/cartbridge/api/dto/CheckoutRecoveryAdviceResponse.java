package com.cartbridge.api.dto;

public record CheckoutRecoveryAdviceResponse(
        String detectedIssue,
        String explanation,
        String recommendedNextAction,
        String safetyWarning,
        String adviceSource
) {
    public CheckoutRecoveryAdviceResponse(
            String detectedIssue,
            String explanation,
            String recommendedNextAction,
            String safetyWarning
    ) {
        this(detectedIssue, explanation, recommendedNextAction, safetyWarning, "GEMINI");
    }
}
