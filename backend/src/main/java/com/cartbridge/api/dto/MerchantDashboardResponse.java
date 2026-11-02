package com.cartbridge.api.dto;

import com.cartbridge.api.entity.CheckoutStatus;

import java.util.List;
import java.util.Map;

public record MerchantDashboardResponse(
        long totalCheckoutSessions,
        Map<CheckoutStatus, Long> statusCounts,
        List<CheckoutSessionResponse> checkouts
) {
}
