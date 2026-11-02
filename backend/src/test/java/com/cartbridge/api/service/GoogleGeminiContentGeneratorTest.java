package com.cartbridge.api.service;

import com.google.genai.types.HttpOptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleGeminiContentGeneratorTest {
    @Test
    void configuresBoundedSdkCallAndDisablesRetries() {
        HttpOptions options = GoogleGeminiContentGenerator.requestHttpOptions();

        assertEquals(30_000, options.timeout().orElseThrow());
        assertEquals(1, options.retryOptions().orElseThrow().attempts().orElseThrow());
    }

    @Test
    void identifiesSdkTimeoutCausesWithoutTreatingOtherIoFailuresAsTimeouts() {
        assertTrue(GoogleGeminiContentGenerator.hasTimeoutCause(
                new IOException("request failed", new InterruptedIOException())
        ));
        assertFalse(GoogleGeminiContentGenerator.hasTimeoutCause(new IOException("connection failed")));
    }
}
