package com.cartbridge.api.service;

import com.cartbridge.api.dto.CreateCheckoutSessionRequest;
import com.cartbridge.api.entity.CheckoutSession;
import com.cartbridge.api.entity.CheckoutStatus;
import com.cartbridge.api.entity.Product;
import com.cartbridge.api.repository.CheckoutSessionRepository;
import com.cartbridge.api.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CheckoutSessionService {
    private final ProductRepository productRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;

    public CheckoutSessionService(
            ProductRepository productRepository,
            CheckoutSessionRepository checkoutSessionRepository
    ) {
        this.productRepository = productRepository;
        this.checkoutSessionRepository = checkoutSessionRepository;
    }

    public CheckoutSession create(CreateCheckoutSessionRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new InvalidCheckoutRequestException("Unknown product ID."));

        CheckoutSession session = new CheckoutSession(
                UUID.randomUUID(),
                product.id(),
                product.name(),
                request.quantity(),
                product.price(),
                product.price().multiply(java.math.BigDecimal.valueOf(request.quantity())),
                product.currency(),
                CheckoutStatus.CREATED,
                Instant.now()
        );

        return checkoutSessionRepository.save(session);
    }

    public List<CheckoutSession> findAll() {
        return checkoutSessionRepository.findAll();
    }
}
