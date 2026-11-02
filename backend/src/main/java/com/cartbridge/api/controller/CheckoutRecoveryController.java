package com.cartbridge.api.controller;

import com.cartbridge.api.dto.CheckoutRecoveryAdviceResponse;
import com.cartbridge.api.dto.CheckoutRecoveryRequest;
import com.cartbridge.api.service.CheckoutRecoveryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recovery")
public class CheckoutRecoveryController {
    private final CheckoutRecoveryService checkoutRecoveryService;

    public CheckoutRecoveryController(CheckoutRecoveryService checkoutRecoveryService) {
        this.checkoutRecoveryService = checkoutRecoveryService;
    }

    @PostMapping("/advice")
    public CheckoutRecoveryAdviceResponse advice(
            @Valid @RequestBody CheckoutRecoveryRequest request
    ) {
        return checkoutRecoveryService.getAdvice(request);
    }
}
