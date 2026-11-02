package com.cartbridge.api.controller;

import com.cartbridge.api.dto.CancelPayPalOrderRequest;
import com.cartbridge.api.dto.CapturePayPalOrderRequest;
import com.cartbridge.api.dto.CreatePayPalOrderRequest;
import com.cartbridge.api.dto.PayPalOrderResponse;
import com.cartbridge.api.dto.PayPalConfigurationResponse;
import com.cartbridge.api.dto.PaymentResultResponse;
import com.cartbridge.api.service.PayPalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/paypal")
public class PayPalController {
    private final PayPalService payPalService;

    public PayPalController(PayPalService payPalService) {
        this.payPalService = payPalService;
    }

    @GetMapping("/config")
    public PayPalConfigurationResponse getConfiguration() {
        return payPalService.getConfiguration();
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public PayPalOrderResponse createOrder(@Valid @RequestBody CreatePayPalOrderRequest request) {
        return payPalService.createOrder(request.checkoutSessionId());
    }

    @PostMapping("/capture")
    public PaymentResultResponse capture(@Valid @RequestBody CapturePayPalOrderRequest request) {
        return payPalService.captureOrder(request.checkoutSessionId(), request.paypalOrderId());
    }

    @PostMapping("/cancel")
    public PaymentResultResponse cancel(@Valid @RequestBody CancelPayPalOrderRequest request) {
        return payPalService.cancelOrder(request.checkoutSessionId(), request.paypalOrderId());
    }
}
