package com.cartbridge.api.service;

import com.cartbridge.api.config.PayPalProperties;
import com.cartbridge.api.dto.PaymentResultResponse;
import com.cartbridge.api.dto.PayPalConfigurationResponse;
import com.cartbridge.api.dto.PayPalOrderResponse;
import com.cartbridge.api.entity.CheckoutSession;
import com.cartbridge.api.entity.CheckoutStatus;
import com.cartbridge.api.repository.CheckoutSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PayPalService {
    private static final Logger logger = LoggerFactory.getLogger(PayPalService.class);

    private final PayPalProperties properties;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final RestClient.Builder restClientBuilder;
    private volatile String cachedAccessToken;
    private volatile Instant accessTokenExpiresAt = Instant.EPOCH;

    public PayPalService(
            PayPalProperties properties,
            CheckoutSessionRepository checkoutSessionRepository,
            RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.checkoutSessionRepository = checkoutSessionRepository;
        this.restClientBuilder = restClientBuilder;
    }

    public PayPalOrderResponse createOrder(UUID checkoutSessionId) {
        requireConfiguration();
        CheckoutSession session = getSession(checkoutSessionId);

        synchronized (session) {
            if (session.paypalOrderId() != null) {
                return new PayPalOrderResponse(session.paypalOrderId());
            }

            if (session.status() != CheckoutStatus.CREATED) {
                throw new PaymentOperationException("This checkout session cannot create a PayPal order.");
            }
            verifyConfiguredCurrency(session);

            JsonNode order;
            try {
                order = apiClient().post()
                        .uri("/v2/checkout/orders")
                        .headers(headers -> {
                            headers.setBearerAuth(accessToken());
                            headers.set("PayPal-Request-Id", stableRequestId("order", session.id()));
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "intent", "CAPTURE",
                                "purchase_units", List.of(Map.of(
                                        "reference_id", session.id().toString(),
                                        "custom_id", session.id().toString(),
                                        "description", session.productName(),
                                        "amount", Map.of(
                                                "currency_code", properties.getCurrency(),
                                                "value", session.total().toPlainString()
                                        )
                                ))
                        ))
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientResponseException exception) {
                throw new PayPalApiException("PayPal order creation failed.", exception.getStatusCode().value());
            } catch (RestClientException exception) {
                throw new PayPalApiException("PayPal order service is unavailable.", 502);
            }

            String orderId = text(order, "id");
            if (orderId == null) {
                throw new PayPalApiException("PayPal did not create a valid order.", 502);
            }
            JsonNode verifiedOrder = fetchOrder(orderId);
            verifyOrderDetails(verifiedOrder, session, orderId);
            if (!"CREATED".equals(text(verifiedOrder, "status"))) {
                logOrderVerificationFailure(
                        session,
                        orderId,
                        text(verifiedOrder, "id"),
                        verifiedOrder.path("purchase_units"),
                        text(verifiedOrder, "status"),
                        "Unexpected status after order creation"
                );
                throw new PayPalApiException("PayPal did not create a valid order.", 502);
            }
            session.attachPaypalOrder(orderId);
            return new PayPalOrderResponse(orderId);
        }
    }

    public PayPalConfigurationResponse getConfiguration() {
        requireConfiguration();
        return new PayPalConfigurationResponse(properties.getClientId(), properties.getCurrency());
    }

    public PaymentResultResponse captureOrder(UUID checkoutSessionId, String paypalOrderId) {
        requireConfiguration();
        CheckoutSession session = getSession(checkoutSessionId);

        synchronized (session) {
            if (session.paypalOrderId() == null || !session.paypalOrderId().equals(paypalOrderId)) {
                logger.warn(
                        "PayPal capture order association failed: checkoutSessionId={} expectedOrderId={} actualOrderId={} expectedAmount={} expectedCurrency={} checkoutStatus={}",
                        session.id(),
                        session.paypalOrderId(),
                        paypalOrderId,
                        session.total().toPlainString(),
                        session.currency(),
                        session.status()
                );
                throw new PaymentOperationException("PayPal order does not belong to this checkout session.");
            }
            if (session.status() == CheckoutStatus.COMPLETED) {
                return PaymentResultResponse.from(session, "Payment was already verified as completed.");
            }
            if (session.status() == CheckoutStatus.FAILED || session.status() == CheckoutStatus.CANCELLED) {
                return PaymentResultResponse.from(session, statusMessage(session.status()));
            }

            JsonNode order = fetchOrder(paypalOrderId);
            verifyOrderDetails(order, session, paypalOrderId);
            String orderStatus = text(order, "status");

            if (session.status() == CheckoutStatus.PENDING) {
                return reconcilePendingCapture(session, paypalOrderId, order);
            }
            if ("CANCELLED".equals(orderStatus)) {
                session.setStatus(CheckoutStatus.CANCELLED);
                return PaymentResultResponse.from(session, statusMessage(session.status()));
            }
            if ("PENDING".equals(orderStatus)) {
                session.setStatus(CheckoutStatus.PENDING);
                return PaymentResultResponse.from(session, statusMessage(session.status()));
            }
            if ("VOIDED".equals(orderStatus)) {
                session.setStatus(CheckoutStatus.FAILED);
                return PaymentResultResponse.from(session, statusMessage(session.status()));
            }
            if (!"APPROVED".equals(orderStatus)) {
                throw new PaymentOperationException("PayPal buyer approval is required before capture.");
            }
            session.setStatus(CheckoutStatus.APPROVED);

            JsonNode capture;
            try {
                capture = apiClient().post()
                        .uri("/v2/checkout/orders/{orderId}/capture", paypalOrderId)
                        .headers(headers -> {
                            headers.setBearerAuth(accessToken());
                            headers.set("PayPal-Request-Id", stableRequestId("capture", session.id()));
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of())
                        .retrieve()
                        .body(JsonNode.class);
            } catch (PayPalApiException exception) {
                session.setStatus(exception.httpStatus() == 422
                        ? CheckoutStatus.FAILED : CheckoutStatus.PENDING);
                return PaymentResultResponse.from(session, statusMessage(session.status()));
            } catch (RestClientResponseException exception) {
                session.setStatus(exception.getStatusCode().value() == 422
                        ? CheckoutStatus.FAILED : CheckoutStatus.PENDING);
                return PaymentResultResponse.from(session, statusMessage(session.status()));
            } catch (RestClientException exception) {
                session.setStatus(CheckoutStatus.PENDING);
                return PaymentResultResponse.from(session, statusMessage(session.status()));
            }

            return verifyAndRecordCapture(session, paypalOrderId, capture);
        }
    }

    public PaymentResultResponse cancelOrder(UUID checkoutSessionId, String paypalOrderId) {
        CheckoutSession session = getSession(checkoutSessionId);
        synchronized (session) {
            if (session.paypalOrderId() == null || !session.paypalOrderId().equals(paypalOrderId)) {
                throw new PaymentOperationException("PayPal order does not belong to this checkout session.");
            }
            if (session.status() == CheckoutStatus.COMPLETED) {
                return PaymentResultResponse.from(session, "Payment was already verified as completed.");
            }
            if (session.status() != CheckoutStatus.FAILED && session.status() != CheckoutStatus.PENDING) {
                session.setStatus(CheckoutStatus.CANCELLED);
            }
            return PaymentResultResponse.from(session, statusMessage(session.status()));
        }
    }

    private PaymentResultResponse verifyAndRecordCapture(
            CheckoutSession session,
            String requestedOrderId,
            JsonNode captureOrder
    ) {
        if (!requestedOrderId.equals(text(captureOrder, "id"))) {
            logger.warn(
                    "PayPal capture order-ID mismatch: checkoutSessionId={} requestedOrderId={} storedOrderId={} actualOrderId={} checkoutStatus={} paypalStatus={}",
                    session.id(),
                    requestedOrderId,
                    session.paypalOrderId(),
                    text(captureOrder, "id"),
                    session.status(),
                    text(captureOrder, "status")
            );
            throw new PayPalApiException("PayPal capture response did not match the requested order.", 502);
        }

        String paypalStatus = text(captureOrder, "status");
        JsonNode purchaseUnits = captureOrder.path("purchase_units");
        if (!purchaseUnits.isArray() || purchaseUnits.size() != 1) {
            logOrderVerificationFailure(
                    session,
                    requestedOrderId,
                    text(captureOrder, "id"),
                    purchaseUnits,
                    paypalStatus,
                    "Unexpected capture purchase-unit structure"
            );
            throw new PayPalApiException("PayPal returned an unexpected capture response.", 502);
        }

        JsonNode captures = purchaseUnits.get(0).path("payments").path("captures");
        if (!captures.isArray() || captures.size() != 1) {
            logCaptureVerificationFailure(
                    session,
                    requestedOrderId,
                    null,
                    null,
                    null,
                    paypalStatus,
                    "Unexpected capture count"
            );
            throw new PayPalApiException("PayPal returned an unexpected capture response.", 502);
        }

        JsonNode capture = captures.get(0);
        String captureStatus = text(capture, "status");
        String captureId = text(capture, "id");
        JsonNode capturedAmount = capture.path("amount");
        if ("COMPLETED".equals(captureStatus)) {
            if (!"COMPLETED".equals(paypalStatus)
                    || captureId == null
                    || !matchesAmount(capturedAmount, session)) {
                logCaptureVerificationFailure(
                        session,
                        requestedOrderId,
                        captureId,
                        text(capturedAmount, "value"),
                        text(capturedAmount, "currency_code"),
                        captureStatus,
                        "Capture response did not verify completed amount/currency"
                );
                throw new PayPalApiException("PayPal capture could not be verified.", 502);
            }

            JsonNode verifiedOrder = fetchOrder(requestedOrderId);
            verifyOrderDetails(verifiedOrder, session, requestedOrderId);
            if (!"COMPLETED".equals(text(verifiedOrder, "status"))) {
                logOrderVerificationFailure(
                        session,
                        requestedOrderId,
                        text(verifiedOrder, "id"),
                        verifiedOrder.path("purchase_units"),
                        text(verifiedOrder, "status"),
                        "Canonical order is not completed after capture"
                );
                throw new PayPalApiException("PayPal capture could not be verified.", 502);
            }

            JsonNode verifiedCaptures = verifiedOrder.path("purchase_units").get(0)
                    .path("payments").path("captures");
            if (!verifiedCaptures.isArray() || verifiedCaptures.size() != 1) {
                logCaptureVerificationFailure(
                        session,
                        requestedOrderId,
                        captureId,
                        text(capturedAmount, "value"),
                        text(capturedAmount, "currency_code"),
                        text(verifiedOrder, "status"),
                        "Canonical order returned an unexpected capture count"
                );
                throw new PayPalApiException("PayPal capture could not be verified.", 502);
            }
            JsonNode verifiedCapture = verifiedCaptures.get(0);
            if (!captureId.equals(text(verifiedCapture, "id"))
                    || !"COMPLETED".equals(text(verifiedCapture, "status"))
                    || !matchesAmount(verifiedCapture.path("amount"), session)
                    || !matchesAmount(verifiedCapture.path("amount"), capturedAmount)) {
                logCaptureVerificationFailure(
                        session,
                        requestedOrderId,
                        captureId,
                        text(verifiedCapture.path("amount"), "value"),
                        text(verifiedCapture.path("amount"), "currency_code"),
                        text(verifiedCapture, "status"),
                        "Canonical capture does not match capture response"
                );
                throw new PayPalApiException("PayPal capture could not be verified.", 502);
            }

            session.complete(captureId);
            return PaymentResultResponse.from(session, statusMessage(session.status()));
        }
        if ("PENDING".equals(captureStatus)) {
            session.setStatus(CheckoutStatus.PENDING);
        } else if ("DECLINED".equals(captureStatus) || "FAILED".equals(captureStatus)) {
            session.setStatus(CheckoutStatus.FAILED);
        } else {
            logCaptureVerificationFailure(
                    session,
                    requestedOrderId,
                    captureId,
                    text(capturedAmount, "value"),
                    text(capturedAmount, "currency_code"),
                    captureStatus,
                    "Unsupported capture status"
            );
            throw new PayPalApiException("PayPal capture could not be verified.", 502);
        }
        return PaymentResultResponse.from(session, statusMessage(session.status()));
    }

    private JsonNode fetchOrder(String orderId) {
        return apiClient().get()
                .uri("/v2/checkout/orders/{orderId}", orderId)
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve()
                .body(JsonNode.class);
    }

    private synchronized String accessToken() {
        if (cachedAccessToken != null && Instant.now().isBefore(accessTokenExpiresAt)) {
            return cachedAccessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        try {
            JsonNode response = apiClient().post()
                    .uri("/v1/oauth2/token")
                    .headers(headers -> headers.setBasicAuth(
                            properties.getClientId(),
                            properties.getClientSecret(),
                            StandardCharsets.UTF_8
                    ))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            String token = text(response, "access_token");
            if (token == null) {
                throw new PayPalApiException("PayPal authentication returned no access token.", 502);
            }
            long expiresIn = response.path("expires_in").asLong(300);
            cachedAccessToken = token;
            accessTokenExpiresAt = Instant.now().plusSeconds(Math.max(1, expiresIn - 60));
            return token;
        } catch (RestClientResponseException exception) {
            throw new PayPalApiException("PayPal authentication failed.", exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new PayPalApiException("PayPal authentication service is unavailable.", 502);
        }
    }

    private RestClient apiClient() {
        return restClientBuilder.clone().baseUrl(properties.getBaseUrl()).build();
    }

    private void verifyOrderDetails(JsonNode order, CheckoutSession session, String expectedOrderId) {
        String actualOrderId = text(order, "id");
        String status = text(order, "status");
        if (order == null || !expectedOrderId.equals(actualOrderId)) {
            logger.warn(
                    "PayPal order-ID verification failed: checkoutSessionId={} expectedOrderId={} storedOrderId={} actualOrderId={} expectedAmount={} expectedCurrency={} paypalStatus={}",
                    session.id(),
                    expectedOrderId,
                    session.paypalOrderId(),
                    actualOrderId,
                    session.total().toPlainString(),
                    session.currency(),
                    status
            );
            throw new PayPalApiException("PayPal returned an unexpected order.", 502);
        }
        JsonNode units = order.path("purchase_units");
        verifyPurchaseUnits(units, session, expectedOrderId, status);
    }

    private void verifyPurchaseUnits(
            JsonNode units,
            CheckoutSession session,
            String paypalOrderId,
            String paypalStatus
    ) {
        if (!units.isArray() || units.size() != 1) {
            logOrderVerificationFailure(
                    session,
                    paypalOrderId,
                    paypalOrderId,
                    units,
                    paypalStatus,
                    "Unexpected purchase-unit structure"
            );
            throw new PayPalApiException("PayPal order details do not match this checkout.", 502);
        }
        JsonNode unit = units.get(0);
        String sessionId = session.id().toString();
        String referenceId = text(unit, "reference_id");
        String customId = text(unit, "custom_id");
        JsonNode amount = unit.path("amount");
        boolean amountMatches = matchesAmount(amount, session);
        boolean referenceMatches = sessionId.equals(referenceId);
        boolean customIdMatches = sessionId.equals(customId);
        if (!referenceMatches || !customIdMatches || !amountMatches) {
            logger.warn(
                    "PayPal purchase-unit verification failed: checkoutSessionId={} expectedOrderId={} storedOrderId={} actualOrderId={} expectedAmount={} actualAmount={} expectedCurrency={} actualCurrency={} referenceIdMatches={} customIdMatches={} checkoutStatus={} paypalStatus={}",
                    session.id(),
                    paypalOrderId,
                    session.paypalOrderId(),
                    paypalOrderId,
                    session.total().toPlainString(),
                    text(amount, "value"),
                    session.currency(),
                    text(amount, "currency_code"),
                    referenceMatches,
                    customIdMatches,
                    session.status(),
                    paypalStatus
            );
            throw new PayPalApiException("PayPal order amount or currency does not match checkout.", 502);
        }
    }

    private boolean matchesAmount(JsonNode amount, JsonNode expectedAmount) {
        String amountValue = text(amount, "value");
        String currency = text(amount, "currency_code");
        String expectedValue = text(expectedAmount, "value");
        String expectedCurrency = text(expectedAmount, "currency_code");
        if (amountValue == null || currency == null || expectedValue == null || expectedCurrency == null) {
            return false;
        }
        try {
            return new BigDecimal(amountValue).compareTo(new BigDecimal(expectedValue)) == 0
                    && currency.equals(expectedCurrency);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean matchesAmount(JsonNode amount, CheckoutSession session) {
        String amountValue = text(amount, "value");
        String currency = text(amount, "currency_code");
        if (amountValue == null || currency == null) {
            return false;
        }
        try {
            return new BigDecimal(amountValue).compareTo(session.total()) == 0
                    && session.currency().equals(currency)
                    && properties.getCurrency().equals(currency);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void logOrderVerificationFailure(
            CheckoutSession session,
            String expectedOrderId,
            String actualOrderId,
            JsonNode units,
            String paypalStatus,
            String reason
    ) {
        JsonNode unit = units != null && units.isArray() && units.size() == 1
                ? units.get(0)
                : null;
        JsonNode amount = unit == null ? null : unit.path("amount");
        logger.warn(
                "PayPal order verification failed: reason={} checkoutSessionId={} expectedOrderId={} storedOrderId={} actualOrderId={} expectedAmount={} actualAmount={} expectedCurrency={} actualCurrency={} purchaseUnitCount={} purchaseUnitsType={} referenceId={} customId={} checkoutStatus={} paypalStatus={}",
                reason,
                session.id(),
                expectedOrderId,
                session.paypalOrderId(),
                actualOrderId,
                session.total().toPlainString(),
                text(amount, "value"),
                session.currency(),
                text(amount, "currency_code"),
                units != null && units.isArray() ? units.size() : null,
                units == null ? "missing" : units.getNodeType(),
                text(unit, "reference_id"),
                text(unit, "custom_id"),
                session.status(),
                paypalStatus
        );
    }

    private void logCaptureVerificationFailure(
            CheckoutSession session,
            String paypalOrderId,
            String captureId,
            String actualAmount,
            String actualCurrency,
            String paypalStatus,
            String reason
    ) {
        logger.warn(
                "PayPal capture verification failed: reason={} checkoutSessionId={} expectedOrderId={} storedOrderId={} captureId={} expectedAmount={} actualAmount={} expectedCurrency={} actualCurrency={} checkoutStatus={} paypalStatus={}",
                reason,
                session.id(),
                paypalOrderId,
                session.paypalOrderId(),
                captureId,
                session.total().toPlainString(),
                actualAmount,
                session.currency(),
                actualCurrency,
                session.status(),
                paypalStatus
        );
    }

    private void verifyConfiguredCurrency(CheckoutSession session) {
        if (!session.currency().equals(properties.getCurrency())) {
            throw new PayPalConfigurationException(
                    "Configured PayPal currency does not match the checkout session currency."
            );
        }
    }

    private void requireConfiguration() {
        if (!properties.hasCredentials()) {
            throw new PayPalConfigurationException(
                    "PayPal Sandbox is not configured. Set PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET."
            );
        }
        if (!properties.isSandboxUrl()) {
            throw new PayPalConfigurationException(
                    "PayPal integration is restricted to the Sandbox API host."
            );
        }
    }

    private CheckoutSession getSession(UUID id) {
        return checkoutSessionRepository.findById(id).orElseThrow(CheckoutSessionNotFoundException::new);
    }

    private String stableRequestId(String operation, UUID sessionId) {
        return UUID.nameUUIDFromBytes((operation + ":" + sessionId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private String statusMessage(CheckoutStatus status) {
        return switch (status) {
            case COMPLETED -> "Payment completed and verified by PayPal.";
            case PENDING -> "PayPal is still processing this payment. Check again before retrying.";
            case FAILED -> "PayPal did not complete this payment.";
            case CANCELLED -> "Checkout was cancelled. No payment was captured.";
            case APPROVED -> "Buyer approved the order; capture verification is in progress.";
            case CREATED -> "PayPal order created; buyer approval is required.";
        };
    }

    private PaymentResultResponse reconcilePendingCapture(
            CheckoutSession session,
            String orderId,
            JsonNode order
    ) {
        JsonNode units = order.path("purchase_units");
        JsonNode captures = units.get(0).path("payments").path("captures");
        if (captures.isArray() && captures.size() == 1) {
            JsonNode capture = captures.get(0);
            String status = text(capture, "status");
            if ("COMPLETED".equals(status)) {
                if (!matchesAmount(capture.path("amount"), session)
                        || !"COMPLETED".equals(text(order, "status"))
                        || text(capture, "id") == null) {
                    throw new PayPalApiException("PayPal capture could not be verified.", 502);
                }
                session.complete(text(capture, "id"));
                return PaymentResultResponse.from(session, statusMessage(session.status()));
            }
            if ("DECLINED".equals(status) || "FAILED".equals(status)) {
                session.setStatus(CheckoutStatus.FAILED);
                return PaymentResultResponse.from(session, statusMessage(session.status()));
            }
        }
        if ("CANCELLED".equals(text(order, "status"))) {
            session.setStatus(CheckoutStatus.CANCELLED);
        }
        return PaymentResultResponse.from(session, statusMessage(session.status()));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
