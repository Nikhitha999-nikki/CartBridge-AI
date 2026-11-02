package com.cartbridge.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.config.import=classpath:env-config-test.properties")
class BackendEnvironmentConfigurationTest {
    @Autowired
    private PayPalProperties payPalProperties;

    @Autowired
    private GeminiProperties geminiProperties;

    @Test
    void loadsBackendSettingsFromImportedPropertiesWithoutRequiringGeminiKey() {
        assertTrue(payPalProperties.hasCredentials());
        assertTrue(payPalProperties.isSandboxUrl());
        assertEquals("USD", payPalProperties.getCurrency());
        assertEquals("gemini-test-model", geminiProperties.getModel());
        assertFalse(geminiProperties.isApiKeyConfigured());
    }
}
