package com.cartbridge.api.service;

public interface GeminiContentGenerator {
    String generate(String model, String prompt);
}
