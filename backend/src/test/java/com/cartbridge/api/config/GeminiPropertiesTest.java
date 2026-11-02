package com.cartbridge.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiPropertiesTest {
    @Test
    void reportsWhetherApiKeyIsConfiguredWithoutSerializingIt() throws Exception {
        String apiKey = "test-gemini-key-not-for-use";
        GeminiProperties properties = new GeminiProperties();

        assertFalse(properties.isApiKeyConfigured());
        properties.setApiKey(apiKey);

        assertTrue(properties.isApiKeyConfigured());
        assertFalse(new ObjectMapper().writeValueAsString(properties).contains(apiKey));
        assertFalse(properties.toString().contains(apiKey));
    }

    @Test
    void defaultsToStableFlashModel() {
        assertEquals("gemini-3.8-flash", new GeminiProperties().getModel());
    }

    @Test
    void blankOrMissingApiKeyKeepsGeminiOptional() {
        GeminiProperties properties = new GeminiProperties();

        assertFalse(properties.isApiKeyConfigured());
        properties.setApiKey("   ");
        assertFalse(properties.isApiKeyConfigured());
    }
}
