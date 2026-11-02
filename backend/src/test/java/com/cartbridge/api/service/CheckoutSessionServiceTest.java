package com.cartbridge.api.service;

import com.cartbridge.api.dto.CreateCheckoutSessionRequest;
import com.cartbridge.api.entity.CheckoutSession;
import com.cartbridge.api.entity.CheckoutStatus;
import com.cartbridge.api.repository.CheckoutSessionRepository;
import com.cartbridge.api.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckoutSessionServiceTest {
    private CheckoutSessionService service;
    private CheckoutSessionRepository checkoutSessionRepository;

    @BeforeEach
    void setUp() {
        checkoutSessionRepository = new CheckoutSessionRepository();
        service = new CheckoutSessionService(new ProductRepository(), checkoutSessionRepository);
    }

    @Test
    void createsSessionUsingCataloguePrice() {
        CheckoutSession session = service.create(new CreateCheckoutSessionRequest("wireless-earbuds", 2));

        assertNotNull(session.id());
        assertEquals("Wireless Earbuds", session.productName());
        assertEquals(new BigDecimal("89.99"), session.unitPrice());
        assertEquals(new BigDecimal("179.98"), session.total());
        assertEquals("USD", session.currency());
        assertEquals(CheckoutStatus.CREATED, session.status());
        assertNotNull(session.createdAt());
    }

    @Test
    void rejectsUnknownProductId() {
        InvalidCheckoutRequestException exception = assertThrows(
                InvalidCheckoutRequestException.class,
                () -> service.create(new CreateCheckoutSessionRequest("unknown-product", 1))
        );

        assertEquals("Unknown product ID.", exception.getMessage());
    }

    @Test
    void createsANewSessionAfterPreviousSessionIsCompleted() {
        CheckoutSession completed = service.create(new CreateCheckoutSessionRequest("everyday-backpack", 1));
        completed.attachPaypalOrder("ORDER-123");
        completed.complete("CAPTURE-123");

        CheckoutSession next = service.create(new CreateCheckoutSessionRequest("everyday-backpack", 1));

        assertNotNull(checkoutSessionRepository.findById(completed.id()).orElse(null));
        assertNotNull(next.id());
        assertNotEquals(completed.id(), next.id());
        assertEquals(CheckoutStatus.COMPLETED, completed.status());
        assertEquals(CheckoutStatus.CREATED, next.status());
        assertEquals(null, next.paypalOrderId());
    }

    @Test
    void retrievesAllCheckoutSessions() {
        CheckoutSession first = service.create(new CreateCheckoutSessionRequest("everyday-backpack", 1));
        CheckoutSession second = service.create(new CreateCheckoutSessionRequest("wireless-earbuds", 2));

        assertEquals(2, service.findAll().size());
        assertEquals(
                java.util.Set.of(first.id(), second.id()),
                service.findAll().stream().map(CheckoutSession::id).collect(java.util.stream.Collectors.toSet())
        );
    }
}
