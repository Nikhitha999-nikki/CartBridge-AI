package com.cartbridge.api.service;

import com.cartbridge.api.config.GeminiProperties;
import com.cartbridge.api.dto.CheckoutRecoveryAdviceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiCheckoutRecoveryAdvisorTest {
    private GeminiProperties properties;
    private GeminiContentGenerator contentGenerator;
    private GeminiCheckoutRecoveryAdvisor advisor;

    @BeforeEach
    void setUp() {
        properties = new GeminiProperties();
        properties.setModel("gemini-3.8-flash");
        contentGenerator = mock(GeminiContentGenerator.class);
        advisor = new GeminiCheckoutRecoveryAdvisor(
                properties,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                contentGenerator
        );
    }

    @Test
    void parsesStructuredAdviceFromConfiguredGeminiModel() {
        when(contentGenerator.generate(eq("gemini-3.8-flash"), anyString())).thenReturn("""
                {
                  "detectedIssue":"Checkout page stalled",
                  "explanation":"The page may still be loading.",
                  "recommendedNextAction":"Wait and check checkout status before retrying.",
                  "safetyWarning":"Do not share payment credentials."
                }
                """);

        CheckoutRecoveryAdviceResponse response = advisor.generateAdvice(
                "The page froze",
                context()
        );

        assertEquals("Checkout page stalled", response.detectedIssue());
        assertEquals("The page may still be loading.", response.explanation());
        verify(contentGenerator).generate(eq("gemini-3.8-flash"), anyString());
    }

    @Test
    void sendsOnlySuppliedNonSensitiveCheckoutContextAndProblem() {
        when(contentGenerator.generate(eq("gemini-3.8-flash"), anyString()))
                .thenReturn(validJson());

        advisor.generateAdvice("Checkout page froze", context());

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(contentGenerator).generate(org.mockito.ArgumentMatchers.eq("gemini-3.8-flash"), prompt.capture());
        assertTrue(prompt.getValue().contains("\"status\":\"CREATED\""));
        assertTrue(prompt.getValue().contains("\"total\":68.00"));
        assertTrue(prompt.getValue().contains("Checkout page froze"));
        assertFalse(prompt.getValue().toLowerCase().contains("paypalorderid"));
    }

    @Test
    void rejectsInvalidJsonAndEmptyGeminiOutput() {
        when(contentGenerator.generate(eq("gemini-3.8-flash"), anyString())).thenReturn("{invalid");
        assertThrows(
                CheckoutRecoveryProviderException.class,
                () -> advisor.generateAdvice("problem", context())
        );

        when(contentGenerator.generate(eq("gemini-3.8-flash"), anyString())).thenReturn("");
        assertThrows(
                CheckoutRecoveryProviderException.class,
                () -> advisor.generateAdvice("problem", context())
        );
    }

    private String validJson() {
        return """
                {
                  "detectedIssue":"Checkout page stalled",
                  "explanation":"The page may still be loading.",
                  "recommendedNextAction":"Wait and check checkout status before retrying.",
                  "safetyWarning":"Do not share payment credentials."
                }
                """;
    }

    private CheckoutRecoveryAdvisor.CheckoutContext context() {
        return new CheckoutRecoveryAdvisor.CheckoutContext(
                "CREATED",
                "Everyday Backpack",
                1,
                new BigDecimal("68.00"),
                "USD",
                Instant.parse("2026-10-31T12:00:00Z"),
                false,
                false
        );
    }
}
