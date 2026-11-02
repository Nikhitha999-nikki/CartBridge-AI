package com.cartbridge.api.dto;

import java.time.Instant;

public record ApiErrorResponse(String error, Instant timestamp) {
}
