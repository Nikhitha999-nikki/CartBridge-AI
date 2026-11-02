package com.cartbridge.api.service;

import com.cartbridge.api.config.PayPalProperties;
import com.cartbridge.api.dto.PaymentResultResponse;
import com.cartbridge.api.entity.CheckoutSession;
import com.cartbridge.api.entity.CheckoutStatus;
import com.cartbridge.api.repository.CheckoutSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PayPalServiceTest {
    private static final String BASE_URL = "https://api-m.sandbox.paypal.com";
    private static final String TOKEN_URL = BASE_URL + "/v1/oauth2/token";
    private static final String ORDERS_URL = BASE_URL + "/v2/checkout/orders";

    private PayPalProperties properties;
    private CheckoutSessionRepository sessions;
    private CheckoutSession session;
    private PayPalService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new PayPalProperties();
        properties.setClientId("sandbox-client-id");
        properties.setClientSecret("sandbox-client-secret");
        properties.setBaseUrl(BASE_URL);
        properties.setCurrency("USD");
        sessions = new CheckoutSessionRepository();
        session = new CheckoutSession(
                UUID.randomUUID(),
                "everyday-backpack",
                "Everyday Backpack",
                2,
                new BigDecimal("68.00"),
                new BigDecimal("136.00"),
                "USD",
                CheckoutStatus.CREATED,
                Instant.now()
        );
        sessions.save(session);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new PayPalService(properties, sessions, builder);
    }

    @Test
    void createsOAuthTokenAndPayPalOrderWithServerTotal() {
        expectToken();
        server.expect(requestTo(ORDERS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PayPal-Request-Id", notNullValue()))
                .andExpect(content().json("""
                        {
                          "intent":"CAPTURE",
                          "purchase_units":[{
                            "reference_id":"%s",
                            "custom_id":"%s",
                            "description":"Everyday Backpack",
                            "amount":{"currency_code":"USD","value":"136.00"}
                          }]
                        }
                        """.formatted(session.id(), session.id())))
                .andRespond(withSuccess("""
                        {"id":"ORDER-123","status":"CREATED"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ORDERS_URL + "/ORDER-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(createdOrder("ORDER-123", "136.000", "USD"),
                        MediaType.APPLICATION_JSON));

        assertEquals("ORDER-123", service.createOrder(session.id()).paypalOrderId());
        assertEquals("ORDER-123", session.paypalOrderId());
        server.verify();
    }

    @Test
    void rejectsCanonicalPayPalOrderWhenPurchaseUnitsAreMissing() {
        expectToken();
        server.expect(requestTo(ORDERS_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":"ORDER-123","status":"CREATED"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ORDERS_URL + "/ORDER-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"ORDER-123","status":"CREATED","purchase_units":[]}
                        """, MediaType.APPLICATION_JSON));

        PayPalApiException exception = assertThrows(
                PayPalApiException.class,
                () -> service.createOrder(session.id())
        );

        assertEquals("PayPal order details do not match this checkout.", exception.getMessage());
        assertEquals(null, session.paypalOrderId());
        server.verify();
    }

    @Test
    void reportsOAuthAuthenticationFailureWithoutExposingResponseBody() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\",\"secret\":\"do-not-return\"}"));

        PayPalApiException exception = assertThrows(
                PayPalApiException.class,
                () -> service.createOrder(session.id())
        );

        assertEquals("PayPal authentication failed.", exception.getMessage());
        assertEquals(401, exception.httpStatus());
        server.verify();
    }

    @Test
    void rejectsUnknownCheckoutSession() {
        assertThrows(CheckoutSessionNotFoundException.class, () -> service.createOrder(UUID.randomUUID()));
        server.verify();
    }

    @Test
    void rejectsMismatchedOrderIdBeforeContactingPayPal() {
        session.attachPaypalOrder("ORDER-123");

        assertThrows(
                PaymentOperationException.class,
                () -> service.captureOrder(session.id(), "ORDER-OTHER")
        );
        server.verify();
    }

    @Test
    void capturesAndVerifiesApprovedOrderAndAmount() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        expectApprovedOrder("136.00", "USD");
        server.expect(requestTo(ORDERS_URL + "/ORDER-123/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PayPal-Request-Id", notNullValue()))
                .andRespond(withSuccess(completedCapture("ORDER-123", "CAPTURE-123", "136.00", "USD"),
                        MediaType.APPLICATION_JSON));
        expectCompletedOrder("136.00", "USD", "CAPTURE-123", "136.00", "USD");

        PaymentResultResponse result = service.captureOrder(session.id(), "ORDER-123");

        assertEquals(CheckoutStatus.COMPLETED, result.status());
        assertEquals("CAPTURE-123", result.paypalCaptureId());
        assertEquals(new BigDecimal("136.00"), result.amount());
        server.verify();
    }

    @Test
    void captureResponseMayOmitOrderLevelAmountAndCustomIdWhenCanonicalOrderVerifies() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        expectApprovedOrder("136.00", "USD");
        server.expect(requestTo(ORDERS_URL + "/ORDER-123/capture"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completedCapture("ORDER-123", "CAPTURE-123", "136.000", "USD"),
                        MediaType.APPLICATION_JSON));
        expectCompletedOrder("136.00", "USD", "CAPTURE-123", "136.00", "USD");

        PaymentResultResponse result = service.captureOrder(session.id(), "ORDER-123");

        assertEquals(CheckoutStatus.COMPLETED, result.status());
        assertEquals("CAPTURE-123", result.paypalCaptureId());
        server.verify();
    }

    @Test
    void rejectsCompletedCaptureWhenCapturedAmountIsMissing() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        expectApprovedOrder("136.00", "USD");
        server.expect(requestTo(ORDERS_URL + "/ORDER-123/capture"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completedCaptureWithoutAmount("ORDER-123", "CAPTURE-123"),
                        MediaType.APPLICATION_JSON));

        assertThrows(PayPalApiException.class, () -> service.captureOrder(session.id(), "ORDER-123"));
        assertEquals(CheckoutStatus.APPROVED, session.status());
        server.verify();
    }

    @Test
    void rejectsCompletedCaptureWhenCanonicalOrderOmitsCustomId() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        expectApprovedOrder("136.00", "USD");
        server.expect(requestTo(ORDERS_URL + "/ORDER-123/capture"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completedCapture("ORDER-123", "CAPTURE-123", "136.00", "USD"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(ORDERS_URL + "/ORDER-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(completedOrder(
                        "136.00", "USD", "CAPTURE-123", "136.00", "USD", false
                ), MediaType.APPLICATION_JSON));

        assertThrows(PayPalApiException.class, () -> service.captureOrder(session.id(), "ORDER-123"));
        assertEquals(CheckoutStatus.APPROVED, session.status());
        server.verify();
    }

    @Test
    void failedCaptureIsNotReportedAsCompletedAndIsNotRetried() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        expectApprovedOrder("136.00", "USD");
        server.expect(requestTo(ORDERS_URL + "/ORDER-123/capture"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"name\":\"UNPROCESSABLE_ENTITY\"}"));

        PaymentResultResponse first = service.captureOrder(session.id(), "ORDER-123");
        PaymentResultResponse repeated = service.captureOrder(session.id(), "ORDER-123");

        assertEquals(CheckoutStatus.FAILED, first.status());
        assertEquals(CheckoutStatus.FAILED, repeated.status());
        server.verify();
    }

    @Test
    void repeatedSuccessfulCaptureReturnsSavedResultWithoutAnotherPayPalCapture() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        expectApprovedOrder("136.00", "USD");
        server.expect(requestTo(ORDERS_URL + "/ORDER-123/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PayPal-Request-Id", notNullValue()))
                .andRespond(withSuccess(completedCapture("ORDER-123", "CAPTURE-123", "136.00", "USD"),
                        MediaType.APPLICATION_JSON));
        expectCompletedOrder("136.00", "USD", "CAPTURE-123", "136.00", "USD");

        PaymentResultResponse first = service.captureOrder(session.id(), "ORDER-123");
        PaymentResultResponse repeated = service.captureOrder(session.id(), "ORDER-123");

        assertEquals(CheckoutStatus.COMPLETED, first.status());
        assertEquals(CheckoutStatus.COMPLETED, repeated.status());
        assertEquals(first.paypalCaptureId(), repeated.paypalCaptureId());
        server.verify();
    }

    @Test
    void rejectsAmountMismatchBeforeCapture() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        expectApprovedOrder("1.00", "USD");

        assertThrows(PayPalApiException.class, () -> service.captureOrder(session.id(), "ORDER-123"));
        server.verify();
    }

    @Test
    void rejectsCurrencyMismatchInCaptureResponse() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        expectApprovedOrder("136.00", "USD");
        server.expect(requestTo(ORDERS_URL + "/ORDER-123/capture"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completedCapture("ORDER-123", "CAPTURE-123", "136.00", "EUR"),
                        MediaType.APPLICATION_JSON));

        assertThrows(PayPalApiException.class, () -> service.captureOrder(session.id(), "ORDER-123"));
        assertEquals(CheckoutStatus.APPROVED, session.status());
        server.verify();
    }

    @Test
    void approvedButPendingPayPalOrderIsNotCapturedOrReportedAsCompleted() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        server.expect(requestTo(ORDERS_URL + "/ORDER-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(order("ORDER-123", "PENDING", "136.00", "USD"),
                        MediaType.APPLICATION_JSON));

        PaymentResultResponse result = service.captureOrder(session.id(), "ORDER-123");

        assertEquals(CheckoutStatus.PENDING, result.status());
        server.verify();
    }

    @Test
    void captureUsesStableIdempotencyRequestId() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        expectApprovedOrder("136.00", "USD");
        String expectedRequestId = UUID.nameUUIDFromBytes(
                ("capture:" + session.id()).getBytes(StandardCharsets.UTF_8)
        ).toString();
        server.expect(requestTo(ORDERS_URL + "/ORDER-123/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PayPal-Request-Id", expectedRequestId))
                .andRespond(withSuccess(completedCapture("ORDER-123", "CAPTURE-123", "136.00", "USD"),
                        MediaType.APPLICATION_JSON));
        expectCompletedOrder("136.00", "USD", "CAPTURE-123", "136.00", "USD");

        assertEquals(
                CheckoutStatus.COMPLETED,
                service.captureOrder(session.id(), "ORDER-123").status()
        );
        server.verify();
    }

    @Test
    void refusesToCallPayPalWhenCredentialsAreMissing() {
        properties.setClientSecret("");

        PayPalConfigurationException exception = assertThrows(
                PayPalConfigurationException.class,
                () -> service.createOrder(session.id())
        );

        assertEquals(
                "PayPal Sandbox is not configured. Set PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET.",
                exception.getMessage()
        );
        server.verify();
    }

    @Test
    void refusesNonSandboxApiHosts() {
        properties.setBaseUrl("https://api-m.paypal.com");

        assertThrows(PayPalConfigurationException.class, () -> service.getConfiguration());
        server.verify();
    }

    @Test
    void recordsDeclinedCaptureAsFailed() {
        session.attachPaypalOrder("ORDER-123");
        expectToken();
        expectApprovedOrder("136.00", "USD");
        server.expect(requestTo(ORDERS_URL + "/ORDER-123/capture"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "id":"ORDER-123",
                          "status":"COMPLETED",
                          "purchase_units":[{
                            "reference_id":"%s",
                            "custom_id":"%s",
                            "amount":{"currency_code":"USD","value":"136.00"},
                            "payments":{"captures":[{
                              "id":"CAPTURE-DECLINED",
                              "status":"DECLINED",
                              "amount":{"currency_code":"USD","value":"136.00"}
                            }]}
                          }]
                        }
                        """.formatted(session.id(), session.id()), MediaType.APPLICATION_JSON));

        PaymentResultResponse result = service.captureOrder(session.id(), "ORDER-123");

        assertEquals(CheckoutStatus.FAILED, result.status());
        assertEquals(null, result.paypalCaptureId());
        server.verify();
    }

    private void expectToken() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token":"test-access-token","token_type":"Bearer","expires_in":900}
                        """, MediaType.APPLICATION_JSON));
    }

    private void expectApprovedOrder(String amount, String currency) {
        server.expect(requestTo(ORDERS_URL + "/ORDER-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(order("ORDER-123", "APPROVED", amount, currency),
                        MediaType.APPLICATION_JSON));
    }

    private void expectCompletedOrder(
            String orderAmount,
            String orderCurrency,
            String captureId,
            String captureAmount,
            String captureCurrency
    ) {
        server.expect(requestTo(ORDERS_URL + "/ORDER-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(completedOrder(
                        orderAmount, orderCurrency, captureId, captureAmount, captureCurrency, true
                ), MediaType.APPLICATION_JSON));
    }

    private String createdOrder(String orderId, String amount, String currency) {
        return order(orderId, "CREATED", amount, currency);
    }

    private String order(String orderId, String status, String amount, String currency) {
        return """
                {
                  "id":"%s",
                  "status":"%s",
                  "purchase_units":[{
                    "reference_id":"%s",
                    "custom_id":"%s",
                    "amount":{"currency_code":"%s","value":"%s"}
                  }]
                }
                """.formatted(orderId, status, session.id(), session.id(), currency, amount);
    }

    private String completedCapture(String orderId, String captureId, String amount, String currency) {
        return """
                {
                  "id":"%s",
                  "status":"COMPLETED",
                  "purchase_units":[{
                    "reference_id":"%s",
                    "payments":{"captures":[{
                      "id":"%s",
                      "status":"COMPLETED",
                      "amount":{"currency_code":"%s","value":"%s"}
                    }]}
                  }]
                }
                """.formatted(orderId, session.id(), captureId, currency, amount);
    }

    private String completedCaptureWithoutAmount(String orderId, String captureId) {
        return """
                {
                  "id":"%s",
                  "status":"COMPLETED",
                  "purchase_units":[{
                    "reference_id":"%s",
                    "payments":{"captures":[{
                      "id":"%s",
                      "status":"COMPLETED"
                    }]}
                  }]
                }
                """.formatted(orderId, session.id(), captureId);
    }

    private String completedOrder(
            String orderAmount,
            String orderCurrency,
            String captureId,
            String captureAmount,
            String captureCurrency,
            boolean includeCustomId
    ) {
        String customId = includeCustomId ? "\"custom_id\":\"" + session.id() + "\"," : "";
        return """
                {
                  "id":"ORDER-123",
                  "status":"COMPLETED",
                  "purchase_units":[{
                    "reference_id":"%s",
                    %s
                    "amount":{"currency_code":"%s","value":"%s"},
                    "payments":{"captures":[{
                      "id":"%s",
                      "status":"COMPLETED",
                      "amount":{"currency_code":"%s","value":"%s"}
                    }]}
                  }]
                }
                """.formatted(
                session.id(), customId, orderCurrency, orderAmount,
                captureId, captureCurrency, captureAmount
        );
    }
}
