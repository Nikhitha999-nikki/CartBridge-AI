package com.cartbridge.api.entity;

import java.math.BigDecimal;

public record Product(
        String id,
        String name,
        String description,
        BigDecimal price,
        String currency
) {
}
