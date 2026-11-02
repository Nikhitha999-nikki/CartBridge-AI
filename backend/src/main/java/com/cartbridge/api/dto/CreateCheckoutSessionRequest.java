package com.cartbridge.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCheckoutSessionRequest(
        @NotBlank @Size(max = 64) String productId,
        @Min(1) @Max(10) int quantity
) {
}
