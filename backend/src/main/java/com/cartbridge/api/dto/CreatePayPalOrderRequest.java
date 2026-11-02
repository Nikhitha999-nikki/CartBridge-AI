package com.cartbridge.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreatePayPalOrderRequest(@NotNull UUID checkoutSessionId) {
}
