package com.cartbridge.api.controller;

import com.cartbridge.api.dto.CheckoutSessionResponse;
import com.cartbridge.api.dto.CreateCheckoutSessionRequest;
import com.cartbridge.api.service.CheckoutSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checkout-sessions")
public class CheckoutSessionController {
    private final CheckoutSessionService checkoutSessionService;

    public CheckoutSessionController(CheckoutSessionService checkoutSessionService) {
        this.checkoutSessionService = checkoutSessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutSessionResponse create(@Valid @RequestBody CreateCheckoutSessionRequest request) {
        return CheckoutSessionResponse.from(checkoutSessionService.create(request));
    }
}
