package com.cartbridge.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CapturePayPalOrderRequest(
        @NotNull UUID checkoutSessionId,
        @NotBlank @Size(max = 64) String paypalOrderId
) {
}
