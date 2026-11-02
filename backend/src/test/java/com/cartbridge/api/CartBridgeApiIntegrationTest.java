package com.cartbridge.api;

import com.cartbridge.api.dto.CheckoutRecoveryAdviceResponse;
import com.cartbridge.api.controller.ApiExceptionHandler;
import com.cartbridge.api.dto.ApiErrorResponse;
import com.cartbridge.api.config.GeminiProperties;
import com.cartbridge.api.entity.CheckoutSession;
import com.cartbridge.api.entity.CheckoutStatus;
import com.cartbridge.api.repository.CheckoutSessionRepository;
import com.cartbridge.api.service.CheckoutRecoveryAdvisor;
import com.cartbridge.api.service.CheckoutRecoveryProviderException;
import com.cartbridge.api.service.CheckoutRecoveryTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesRegex;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CartBridgeApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CheckoutSessionRepository checkoutSessionRepository;

    @Autowired
    private GeminiProperties geminiProperties;

    @MockitoBean
    private CheckoutRecoveryAdvisor recoveryAdvisor;

    @Test
    void healthEndpointReportsUpAndAllowsAngularOrigin() throws Exception {
        mockMvc.perform(get("/api/health").header("Origin", "http://localhost:4200"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void productsEndpointReturnsThreeProducts() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value("everyday-backpack"))
                .andExpect(jsonPath("$[0].price").value(68.00))
                .andExpect(jsonPath("$[1].id").value("wireless-earbuds"))
                .andExpect(jsonPath("$[2].id").value("ceramic-coffee-set"));
    }

    @Test
    void createsCheckoutSessionFromServerCatalogue() throws Exception {
        mockMvc.perform(post("/api/checkout-sessions")
                        .contentType("application/json")
                        .content("""
                                {
                                  "productId": "everyday-backpack",
                                  "quantity": 2,
                                  "price": 0.01,
                                  "total": 0.02
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value("everyday-backpack"))
                .andExpect(jsonPath("$.productName").value("Everyday Backpack"))
                .andExpect(jsonPath("$.unitPrice").value(68.00))
                .andExpect(jsonPath("$.total").value(136.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.id").value(matchesRegex(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                )));
    }

    @Test
    void rejectsInvalidQuantityAndUnknownProduct() throws Exception {
        mockMvc.perform(post("/api/checkout-sessions")
                        .contentType("application/json")
                        .content("""
                                {"productId":"everyday-backpack","quantity":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(post("/api/checkout-sessions")
                        .contentType("application/json")
                        .content("""
                                {"productId":"not-in-catalogue","quantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown product ID."));
    }

    @Test
    void paypalConfigurationEndpointExplainsMissingCredentials() throws Exception {
        mockMvc.perform(get("/api/paypal/config"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(
                        "PayPal Sandbox is not configured. Set PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET."
                ));
    }

    @Test
    void recoveryAdviceEndpointRejectsInvalidProblemDescriptions() throws Exception {
        mockMvc.perform(post("/api/recovery/advice")
                        .contentType("application/json")
                        .content("""
                                {
                                  "checkoutSessionId":"49b5c470-f1d0-41d9-b9fc-29873c79ec81",
                                  "problemDescription":"   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request. Check the submitted fields."));

        mockMvc.perform(post("/api/recovery/advice")
                        .contentType("application/json")
                        .content("""
                                {
                                  "checkoutSessionId":"49b5c470-f1d0-41d9-b9fc-29873c79ec81"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/recovery/advice")
                        .contentType("application/json")
                        .content("""
                                {
                                  "checkoutSessionId":"49b5c470-f1d0-41d9-b9fc-29873c79ec81",
                                  "problemDescription":"%s"
                                }
                                """.formatted("x".repeat(1001))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recoveryAdviceEndpointMarksGeminiGeneratedAdvice() throws Exception {
        when(recoveryAdvisor.generateAdvice(anyString(), any()))
                .thenReturn(new CheckoutRecoveryAdviceResponse(
                        "Checkout page stalled",
                        "The payment step may not have finished loading.",
                        "Check the payment status before taking another payment action.",
                        "Do not share payment credentials."
                ));
        CheckoutSession session = createRecoverySession(CheckoutStatus.CREATED);

        mockMvc.perform(post("/api/recovery/advice")
                        .contentType("application/json")
                        .content(recoveryRequest(session.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adviceSource").value("GEMINI"))
                .andExpect(jsonPath("$.safetyWarning").isNotEmpty());
    }

    @Test
    void recoveryAdviceEndpointReturnsFallbackWhenGeminiTimesOutOrIsUnavailable() throws Exception {
        when(recoveryAdvisor.generateAdvice(anyString(), any()))
                .thenThrow(new CheckoutRecoveryTimeoutException(new java.io.InterruptedIOException()))
                .thenThrow(new CheckoutRecoveryProviderException());

        for (int attempt = 0; attempt < 2; attempt++) {
            CheckoutSession session = createRecoverySession(CheckoutStatus.CREATED);
            mockMvc.perform(post("/api/recovery/advice")
                            .contentType("application/json")
                            .content(recoveryRequest(session.id())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.adviceSource").value("FALLBACK"))
                    .andExpect(jsonPath("$.safetyWarning").isNotEmpty());
        }
    }

    @Test
    void merchantDashboardReturnsBackendSessionStatusAndPaymentReferences() throws Exception {
        CheckoutSession session = createRecoverySession(CheckoutStatus.COMPLETED);
        session.attachPaypalOrder("sandbox-order-reference");
        session.complete("sandbox-capture-reference");

        mockMvc.perform(get("/api/merchant/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCheckoutSessions").isNumber())
                .andExpect(jsonPath("$.statusCounts.COMPLETED").isNumber())
                .andExpect(jsonPath("$.checkouts[0].id").value(session.id().toString()))
                .andExpect(jsonPath("$.checkouts[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.checkouts[0].productName").value("Everyday Backpack"))
                .andExpect(jsonPath("$.checkouts[0].quantity").value(1))
                .andExpect(jsonPath("$.checkouts[0].total").value(68.00))
                .andExpect(jsonPath("$.checkouts[0].currency").value("USD"))
                .andExpect(jsonPath("$.checkouts[0].paypalOrderId").value("sandbox-order-reference"))
                .andExpect(jsonPath("$.checkouts[0].paypalCaptureId").value("sandbox-capture-reference"));
    }

    @Test
    void merchantRecoveryHistoryStoresAdviceAndUsesBackendPaymentStatus() throws Exception {
        when(recoveryAdvisor.generateAdvice(anyString(), any()))
                .thenReturn(new CheckoutRecoveryAdviceResponse(
                        "Checkout page stalled",
                        "The payment step may not have finished loading.",
                        "Check checkout status before taking another payment action.",
                        "Do not share payment credentials."
                ));
        CheckoutSession session = createRecoverySession(CheckoutStatus.CREATED);

        mockMvc.perform(post("/api/recovery/advice")
                        .contentType("application/json")
                        .content("""
                                {
                                  "checkoutSessionId":"%s",
                                  "problemDescription":"Checkout page stopped responding.",
                                  "paymentStatus":"COMPLETED"
                                }
                                """.formatted(session.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adviceSource").value("GEMINI"));

        mockMvc.perform(get("/api/merchant/recovery-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].checkoutSessionId").value(session.id().toString()))
                .andExpect(jsonPath("$[0].problemDescription")
                        .value("Checkout page stopped responding."))
                .andExpect(jsonPath("$[0].detectedIssue").value("Checkout page stalled"))
                .andExpect(jsonPath("$[0].explanation").exists())
                .andExpect(jsonPath("$[0].recommendedNextAction")
                        .value("Check checkout status before taking another payment action."))
                .andExpect(jsonPath("$[0].safetyWarning").isNotEmpty())
                .andExpect(jsonPath("$[0].adviceSource").value("GEMINI"))
                .andExpect(jsonPath("$[0].paymentStatus").value("CREATED"))
                .andExpect(jsonPath("$[0].timestamp").isNotEmpty());
    }

    @Test
    void geminiTimeoutReturnsControlledGatewayTimeoutResponse() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<ApiErrorResponse> response =
                handler.handleCheckoutRecoveryTimeout(new CheckoutRecoveryTimeoutException(null));

        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
        org.junit.jupiter.api.Assertions.assertEquals(
                "AI assistance is taking too long. Please try again.",
                response.getBody().error()
        );
    }

    private CheckoutSession createRecoverySession(CheckoutStatus status) {
        geminiProperties.setApiKey("configured-for-mocked-advisor");
        CheckoutSession session = new CheckoutSession(
                UUID.randomUUID(),
                "everyday-backpack",
                "Everyday Backpack",
                1,
                new BigDecimal("68.00"),
                new BigDecimal("68.00"),
                "USD",
                status,
                Instant.now()
        );
        return checkoutSessionRepository.save(session);
    }

    private String recoveryRequest(UUID checkoutSessionId) {
        return """
                {
                  "checkoutSessionId":"%s",
                  "problemDescription":"Checkout page stopped responding."
                }
                """.formatted(checkoutSessionId);
    }
}
