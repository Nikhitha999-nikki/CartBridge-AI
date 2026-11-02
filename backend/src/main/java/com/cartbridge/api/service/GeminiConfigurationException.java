package com.cartbridge.api.service;

public class GeminiConfigurationException extends RuntimeException {
    public GeminiConfigurationException() {
        super("Gemini recovery advice is not configured. Set GOOGLE_API_KEY.");
    }
}
