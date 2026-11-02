package com.cartbridge.api.controller;

import com.cartbridge.api.dto.ApiErrorResponse;
import com.cartbridge.api.service.InvalidCheckoutRequestException;
import com.cartbridge.api.service.CheckoutSessionNotFoundException;
import com.cartbridge.api.service.CheckoutRecoveryProviderException;
import com.cartbridge.api.service.CheckoutRecoveryTimeoutException;
import com.cartbridge.api.service.GeminiConfigurationException;
import com.cartbridge.api.service.PayPalApiException;
import com.cartbridge.api.service.PayPalConfigurationException;
import com.cartbridge.api.service.PaymentOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(InvalidCheckoutRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCheckoutRequest(
            InvalidCheckoutRequestException exception
    ) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "Invalid request. Check the submitted fields.");
    }

    @ExceptionHandler(CheckoutSessionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingCheckoutSession(
            CheckoutSessionNotFoundException exception
    ) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(PaymentOperationException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentOperation(PaymentOperationException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(PayPalConfigurationException.class)
    public ResponseEntity<ApiErrorResponse> handlePayPalConfiguration(PayPalConfigurationException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    @ExceptionHandler(PayPalApiException.class)
    public ResponseEntity<ApiErrorResponse> handlePayPalApi(PayPalApiException exception) {
        return error(HttpStatus.BAD_GATEWAY, exception.getMessage());
    }

    @ExceptionHandler(GeminiConfigurationException.class)
    public ResponseEntity<ApiErrorResponse> handleGeminiConfiguration(GeminiConfigurationException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    @ExceptionHandler(CheckoutRecoveryProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleCheckoutRecoveryProvider(
            CheckoutRecoveryProviderException exception
    ) {
        return error(HttpStatus.BAD_GATEWAY, exception.getMessage());
    }

    @ExceptionHandler(CheckoutRecoveryTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleCheckoutRecoveryTimeout(
            CheckoutRecoveryTimeoutException exception
    ) {
        return error(
                HttpStatus.GATEWAY_TIMEOUT,
                "AI assistance is taking too long. Please try again."
        );
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiErrorResponse> handlePayPalConnectionFailure(RestClientException exception) {
        return error(HttpStatus.BAD_GATEWAY, "PayPal Sandbox is currently unavailable.");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(message, Instant.now()));
    }
}
