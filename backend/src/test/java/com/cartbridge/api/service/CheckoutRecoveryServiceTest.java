package com.cartbridge.api.service;

import com.cartbridge.api.config.GeminiProperties;
import com.cartbridge.api.dto.CheckoutRecoveryAdviceResponse;
import com.cartbridge.api.dto.CheckoutRecoveryRequest;
import com.cartbridge.api.entity.CheckoutSession;
import com.cartbridge.api.entity.CheckoutStatus;
import com.cartbridge.api.repository.CheckoutRecoveryHistoryRepository;
import com.cartbridge.api.repository.CheckoutSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckoutRecoveryServiceTest {
    private static final String API_KEY = "test-api-key-not-for-use";

    private GeminiProperties properties;
    private CheckoutSessionRepository sessions;
    private CheckoutRecoveryHistoryRepository recoveryHistory;
    private CheckoutRecoveryAdvisor advisor;
    private CheckoutRecoveryService service;
    private CheckoutSession session;

    @BeforeEach
    void setUp() {
        properties = new GeminiProperties();
        properties.setApiKey(API_KEY);
        sessions = new CheckoutSessionRepository();
        recoveryHistory = new CheckoutRecoveryHistoryRepository();
        advisor = mock(CheckoutRecoveryAdvisor.class);
        service = new CheckoutRecoveryService(properties, sessions, recoveryHistory, advisor);
        session = new CheckoutSession(
                UUID.randomUUID(),
                "everyday-backpack",
                "Everyday Backpack",
                2,
                new BigDecimal("68.00"),
                new BigDecimal("136.00"),
                "USD",
                CheckoutStatus.CREATED,
                Instant.parse("2026-10-31T12:00:00Z")
        );
        sessions.save(session);
    }

    @Test
    void usesServerCheckoutContextForGeminiAdvice() {
        CheckoutRecoveryAdviceResponse expected = advice();
        when(advisor.generateAdvice(eq("Checkout got stuck"), any()))
                .thenReturn(expected);

        CheckoutRecoveryAdviceResponse actual = service.getAdvice(
                new CheckoutRecoveryRequest(session.id(), "Checkout got stuck")
        );

        assertEquals(expected, actual);
        assertEquals(1, recoveryHistory.findAll().size());
        assertEquals("Checkout got stuck", recoveryHistory.findAll().get(0).problemDescription());
        assertEquals(CheckoutStatus.CREATED, recoveryHistory.findAll().get(0).paymentStatus());
        assertEquals("GEMINI", recoveryHistory.findAll().get(0).adviceSource());
        assertFalse(recoveryHistory.findAll().get(0).safetyWarning().isBlank());
        verify(advisor).generateAdvice(eq("Checkout got stuck"), eq(
                new CheckoutRecoveryAdvisor.CheckoutContext(
                        "CREATED",
                        "Everyday Backpack",
                        2,
                        new BigDecimal("136.00"),
                        "USD",
                        session.createdAt(),
                        false,
                        false
                )
        ));
    }

    @Test
    void rejectsUnknownCheckoutSession() {
        CheckoutRecoveryRequest request = new CheckoutRecoveryRequest(
                UUID.randomUUID(),
                "Checkout got stuck"
        );

        assertThrows(CheckoutSessionNotFoundException.class, () -> service.getAdvice(request));
        verify(advisor, never()).generateAdvice(any(), any());
    }

    @Test
    void missingApiKeyReturnsClearlyLabeledFallbackWithoutCallingProvider() {
        properties.setApiKey("");

        session.setStatus(CheckoutStatus.CREATED);
        CheckoutRecoveryAdviceResponse response = service.getAdvice(
                new CheckoutRecoveryRequest(session.id(), "Checkout got stuck")
        );

        assertEquals("FALLBACK", response.adviceSource());
        assertFalse(response.safetyWarning().isBlank());
        assertEquals("FALLBACK", recoveryHistory.findAll().get(0).adviceSource());
        verify(advisor, never()).generateAdvice(any(), any());
    }

    @Test
    void deterministicPaymentSafetyAdviceWorksEvenWithoutApiKey() {
        properties.setApiKey("");
        session.setStatus(CheckoutStatus.APPROVED);

        CheckoutRecoveryAdviceResponse response = service.getAdvice(
                new CheckoutRecoveryRequest(session.id(), "Should I try again?")
        );

        assertEquals("FALLBACK", response.adviceSource());
        assertTrue(response.recommendedNextAction().contains("Wait for backend verification"));
        verify(advisor, never()).generateAdvice(any(), any());
    }

    @Test
    void completedPaymentReturnsBackendVerifiedNoRetryAdvice() {
        session.complete("verified-capture");

        CheckoutRecoveryAdviceResponse response = service.getAdvice(
                new CheckoutRecoveryRequest(session.id(), "Did payment go through?")
        );

        assertEquals("Payment already completed", response.detectedIssue());
        assertTrue(response.explanation().contains("backend has verified"));
        assertTrue(response.recommendedNextAction().contains("Do not pay again"));
        assertTrue(response.safetyWarning().contains("charge you twice"));
        assertEquals("FALLBACK", response.adviceSource());
        assertEquals(CheckoutStatus.COMPLETED, recoveryHistory.findAll().get(0).paymentStatus());
        verify(advisor, never()).generateAdvice(any(), any());
    }

    @Test
    void approvedAndPendingPaymentsMustWaitForBackendVerification() {
        for (CheckoutStatus status : new CheckoutStatus[]{CheckoutStatus.APPROVED, CheckoutStatus.PENDING}) {
            session.setStatus(status);

            CheckoutRecoveryAdviceResponse response = service.getAdvice(
                    new CheckoutRecoveryRequest(session.id(), "Should I try again?")
            );

            assertTrue(response.recommendedNextAction().contains("Wait for backend verification"));
            assertTrue(response.safetyWarning().contains("Do not retry payment"));
            assertEquals("FALLBACK", response.adviceSource());
            assertEquals(status, recoveryHistory.findAll().get(0).paymentStatus());
            verify(advisor, never()).generateAdvice(any(), any());
        }
    }

    @Test
    void geminiTimeoutReturnsSafeFallbackGuidance() {
        when(advisor.generateAdvice(eq("Checkout got stuck"), any()))
                .thenThrow(new CheckoutRecoveryTimeoutException(new java.io.InterruptedIOException()));

        CheckoutRecoveryAdviceResponse response = service.getAdvice(
                new CheckoutRecoveryRequest(session.id(), "Checkout got stuck")
        );

        assertEquals("FALLBACK", response.adviceSource());
        assertFalse(response.safetyWarning().isBlank());
        assertTrue(response.recommendedNextAction().contains("continue checkout"));
        assertEquals(CheckoutStatus.CREATED, recoveryHistory.findAll().get(0).paymentStatus());
    }

    @Test
    void providerUnavailableReturnsSafeFallbackGuidance() {
        when(advisor.generateAdvice(eq("Checkout got stuck"), any()))
                .thenThrow(new CheckoutRecoveryProviderException());

        CheckoutRecoveryAdviceResponse response = service.getAdvice(
                new CheckoutRecoveryRequest(session.id(), "Checkout got stuck")
        );

        assertEquals("FALLBACK", response.adviceSource());
        assertFalse(response.safetyWarning().isBlank());
        assertFalse(response.recommendedNextAction().toLowerCase().contains("retry payment"));
        assertEquals(CheckoutStatus.CREATED, recoveryHistory.findAll().get(0).paymentStatus());
    }

    @Test
    void fallbackStatusGuidanceNeverRetriesWhenPaymentReferenceExists() {
        when(advisor.generateAdvice(any(), any())).thenThrow(new CheckoutRecoveryProviderException());
        for (CheckoutStatus status : new CheckoutStatus[]{CheckoutStatus.CREATED, CheckoutStatus.FAILED,
                CheckoutStatus.CANCELLED}) {
            session.setStatus(status);
            session.attachPaypalOrder("internal-order-reference");

            CheckoutRecoveryAdviceResponse response = service.getAdvice(
                    new CheckoutRecoveryRequest(session.id(), "Should I try payment again?")
            );

            assertEquals("FALLBACK", response.adviceSource());
            assertTrue(response.recommendedNextAction().contains("Do not start or retry payment"));
            assertTrue(response.safetyWarning().contains("uncertain"));
        }
    }

    @Test
    void failedAndCancelledSessionsWithoutPaymentReferencesGiveCautiousNextSteps() {
        when(advisor.generateAdvice(any(), any())).thenThrow(new CheckoutRecoveryProviderException());
        for (CheckoutStatus status : new CheckoutStatus[]{CheckoutStatus.FAILED, CheckoutStatus.CANCELLED}) {
            session.setStatus(status);

            CheckoutRecoveryAdviceResponse response = service.getAdvice(
                    new CheckoutRecoveryRequest(session.id(), "What should I do?")
            );

            assertEquals("FALLBACK", response.adviceSource());
            assertFalse(response.safetyWarning().isBlank());
            assertTrue(response.recommendedNextAction().contains("new checkout"));
            assertTrue(response.safetyWarning().contains("do not try again"));
        }
    }

    @Test
    void rejectsGeneratedPaymentSuccessClaimsAndSensitiveInformationRequests() {
        when(advisor.generateAdvice(eq("Checkout got stuck"), any()))
                .thenReturn(new CheckoutRecoveryAdviceResponse(
                        "Payment completed",
                        "The payment was successful.",
                        "No action needed.",
                        ""
                ));

        CheckoutRecoveryAdviceResponse paymentClaim = service.getAdvice(
                new CheckoutRecoveryRequest(session.id(), "Checkout got stuck")
        );
        assertEquals("FALLBACK", paymentClaim.adviceSource());
        assertFalse(paymentClaim.safetyWarning().isBlank());

        when(advisor.generateAdvice(eq("Checkout got stuck"), any()))
                .thenReturn(new CheckoutRecoveryAdviceResponse(
                        "Checkout information required",
                        "We need more information.",
                        "Please provide your card number to continue.",
                        ""
                ));

        CheckoutRecoveryAdviceResponse sensitiveRequest = service.getAdvice(
                new CheckoutRecoveryRequest(session.id(), "Checkout got stuck")
        );
        assertEquals("FALLBACK", sensitiveRequest.adviceSource());
        assertFalse(sensitiveRequest.recommendedNextAction().toLowerCase().contains("card number"));
    }

    @Test
    void apiKeyIsNotSerializedInAdviceResponses() throws Exception {
        when(advisor.generateAdvice(eq("Checkout got stuck"), any())).thenReturn(advice());

        String response = new ObjectMapper().writeValueAsString(service.getAdvice(
                new CheckoutRecoveryRequest(session.id(), "Checkout got stuck")
        ));

        assertFalse(response.contains(API_KEY));
        assertTrue(response.contains("Payment button did not respond"));
        assertTrue(response.contains("GEMINI"));
        assertTrue(response.contains("Do not share payment credentials"));
    }

    private CheckoutRecoveryAdviceResponse advice() {
        return new CheckoutRecoveryAdviceResponse(
                "Payment button did not respond",
                "The checkout page may not have finished loading.",
                "Reload the checkout and check the payment status before trying again.",
                "Do not share payment credentials or retry while payment status is uncertain."
        );
    }
}
