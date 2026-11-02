package com.cartbridge.api.service;

import com.cartbridge.api.config.GeminiProperties;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class GoogleGeminiContentGenerator implements GeminiContentGenerator {
    private static final int GEMINI_REQUEST_TIMEOUT_MS = 30_000;
    private static final Logger logger = LoggerFactory.getLogger(GoogleGeminiContentGenerator.class);
    private static final Schema RESPONSE_SCHEMA = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(Map.of(
                    "detectedIssue", stringSchema(),
                    "explanation", stringSchema(),
                    "recommendedNextAction", stringSchema(),
                    "safetyWarning", stringSchema()
            ))
            .required(
                    "detectedIssue",
                    "explanation",
                    "recommendedNextAction",
                    "safetyWarning"
            )
            .build();

    private final GeminiProperties properties;
    private final Supplier<Client> clientFactory;
    private volatile Client client;

    @Autowired
    public GoogleGeminiContentGenerator(GeminiProperties properties) {
        this(properties, () -> Client.builder()
                .apiKey(properties.getApiKey())
                .vertexAI(false)
                .httpOptions(requestHttpOptions())
                .build());
    }

    GoogleGeminiContentGenerator(GeminiProperties properties, Supplier<Client> clientFactory) {
        this.properties = properties;
        this.clientFactory = clientFactory;
    }

    @Override
    public String generate(String model, String prompt) {
        Client activeClient = client();
        GenerateContentConfig config = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .responseSchema(RESPONSE_SCHEMA)
                .build();
        long startTime = System.nanoTime();
        boolean succeeded = false;
        try {
            String output = activeClient.models.generateContent(
                    model,
                    prompt,
                    config
            ).text();
            succeeded = true;
            return output;
        } catch (ApiException exception) {
            logger.warn(
                    "Gemini request failed: exceptionClass={} httpStatus={}",
                    exception.getClass().getName(),
                    exception.code()
            );
            throw new CheckoutRecoveryProviderException(exception);
        } catch (GenAiIOException exception) {
            logger.warn(
                    "Gemini request failed: exceptionClass={} httpStatus=unavailable",
                    exception.getClass().getName()
            );
            if (hasTimeoutCause(exception)) {
                throw new CheckoutRecoveryTimeoutException(exception);
            }
            throw new CheckoutRecoveryProviderException(exception);
        } finally {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            logger.info(
                    "GEMINI_GENERATION outcome={} durationMs={}",
                    succeeded ? "success" : "failure",
                    durationMs
            );
        }
    }

    @PreDestroy
    public void close() {
        Client current = client;
        if (current != null) {
            current.close();
        }
    }

    private Client client() {
        Client current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = clientFactory.get();
                    client = current;
                }
            }
        }
        return current;
    }

    private static Schema stringSchema() {
        return Schema.builder().type(Type.Known.STRING).build();
    }

    static HttpOptions requestHttpOptions() {
        return HttpOptions.builder()
                .timeout(GEMINI_REQUEST_TIMEOUT_MS)
                .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                .build();
    }

    static boolean hasTimeoutCause(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
