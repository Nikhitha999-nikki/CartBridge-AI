package com.cartbridge.api.service;

import com.cartbridge.api.config.GeminiProperties;
import com.cartbridge.api.dto.CheckoutRecoveryAdviceResponse;
import com.cartbridge.api.dto.CheckoutRecoveryRequest;
import com.cartbridge.api.entity.CheckoutRecoveryHistoryEntry;
import com.cartbridge.api.entity.CheckoutSession;
import com.cartbridge.api.entity.CheckoutStatus;
import com.cartbridge.api.repository.CheckoutRecoveryHistoryRepository;
import com.cartbridge.api.repository.CheckoutSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.regex.Pattern;

@Service
public class CheckoutRecoveryService {
    private static final Logger logger = LoggerFactory.getLogger(CheckoutRecoveryService.class);
    private static final Pattern PAYMENT_SUCCESS_CLAIM = Pattern.compile(
            "(?i)\\b(?:payment|transaction|charge)\\b.{0,50}"
                    + "\\b(?:successful|succeeded|completed|approved|processed|went through)\\b"
    );
    private static final Pattern SENSITIVE_INFORMATION_REQUEST = Pattern.compile(
            "(?i)\\b(?:please\\s+)?(?:provide|enter|share|send|submit|tell me|give me|type in)"
                    + ".{0,80}\\b(?:card numbers?|credit cards?|debit cards?|passwords?|CVV|CVC|OTP"
                    + "|one[- ]time passwords?|PayPal credentials)\\b"
    );

    private final GeminiProperties geminiProperties;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final CheckoutRecoveryHistoryRepository recoveryHistoryRepository;
    private final CheckoutRecoveryAdvisor advisor;

    public CheckoutRecoveryService(
            GeminiProperties geminiProperties,
            CheckoutSessionRepository checkoutSessionRepository,
            CheckoutRecoveryHistoryRepository recoveryHistoryRepository,
            CheckoutRecoveryAdvisor advisor
    ) {
        this.geminiProperties = geminiProperties;
        this.checkoutSessionRepository = checkoutSessionRepository;
        this.recoveryHistoryRepository = recoveryHistoryRepository;
        this.advisor = advisor;
    }

    public CheckoutRecoveryAdviceResponse getAdvice(CheckoutRecoveryRequest request) {
        long startTime = System.nanoTime();
        boolean succeeded = false;
        try {
            CheckoutSession session = checkoutSessionRepository.findById(request.checkoutSessionId())
                    .orElseThrow(CheckoutSessionNotFoundException::new);
            CheckoutRecoveryAdviceResponse advice = getAdviceInternal(session, request);
            synchronized (session) {
                advice = currentPaymentGuidance(session, advice);
                recoveryHistoryRepository.save(new CheckoutRecoveryHistoryEntry(
                        session.id(),
                        request.problemDescription(),
                        advice.detectedIssue(),
                        advice.explanation(),
                        advice.recommendedNextAction(),
                        advice.safetyWarning(),
                        advice.adviceSource(),
                        Instant.now(),
                        session.status()
                ));
            }
            succeeded = true;
            return advice;
        } finally {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            logger.info(
                    "RECOVERY_TOTAL outcome={} durationMs={}",
                    succeeded ? "success" : "failure",
                    durationMs
            );
        }
    }

    private CheckoutRecoveryAdviceResponse getAdviceInternal(
            CheckoutSession session,
            CheckoutRecoveryRequest request
    ) {
        if (session.status() == CheckoutStatus.COMPLETED
                || session.status() == CheckoutStatus.APPROVED
                || session.status() == CheckoutStatus.PENDING) {
            return deterministicStatusAdvice(session);
        }
        if (!geminiProperties.isApiKeyConfigured()) {
            return fallbackAdvice(session);
        }

        CheckoutRecoveryAdvisor.CheckoutContext context = new CheckoutRecoveryAdvisor.CheckoutContext(
                session.status().name(),
                session.productName(),
                session.quantity(),
                session.total(),
                session.currency(),
                session.createdAt(),
                session.paypalOrderId() != null,
                session.paypalCaptureId() != null
        );
        try {
            CheckoutRecoveryAdviceResponse advice = advisor.generateAdvice(
                    request.problemDescription(),
                    context
            );
            if (!isValidAdvice(advice)) {
                return fallbackAdvice(session);
            }
            return new CheckoutRecoveryAdviceResponse(
                    advice.detectedIssue(),
                    advice.explanation(),
                    advice.recommendedNextAction(),
                    safeWarning(advice.safetyWarning()),
                    "GEMINI"
            );
        } catch (CheckoutRecoveryTimeoutException | CheckoutRecoveryProviderException exception) {
            return fallbackAdvice(session);
        }
    }

    private CheckoutRecoveryAdviceResponse deterministicStatusAdvice(CheckoutSession session) {
        return switch (session.status()) {
            case COMPLETED -> new CheckoutRecoveryAdviceResponse(
                    "Payment already completed",
                    "The backend has verified that payment for this checkout is complete.",
                    "Do not pay again for this checkout.",
                    "Starting another payment could charge you twice.",
                    "FALLBACK"
            );
            case APPROVED, PENDING -> new CheckoutRecoveryAdviceResponse(
                    "Payment verification is in progress",
                    "The backend has not finished verifying this payment.",
                    "Wait for backend verification to finish before trying another payment.",
                    "Do not retry payment while verification is pending.",
                    "FALLBACK"
            );
            default -> fallbackAdvice(session);
        };
    }

    private CheckoutRecoveryAdviceResponse fallbackAdvice(CheckoutSession session) {
        if (session.status() == CheckoutStatus.COMPLETED
                || session.status() == CheckoutStatus.APPROVED
                || session.status() == CheckoutStatus.PENDING) {
            return deterministicStatusAdvice(session);
        }

        boolean hasPaymentReference = session.paypalOrderId() != null
                || session.paypalCaptureId() != null;
        if (hasPaymentReference) {
            return new CheckoutRecoveryAdviceResponse(
                    "Payment status needs verification",
                    "A PayPal payment reference exists, but the backend has not verified this checkout as complete.",
                    "Do not start or retry payment. Check the payment status with the merchant before taking another payment action.",
                    "The payment outcome is uncertain; another attempt could result in a duplicate charge.",
                    "FALLBACK"
            );
        }

        return switch (session.status()) {
            case CREATED -> new CheckoutRecoveryAdviceResponse(
                    "Checkout has not started payment",
                    "The backend has no PayPal order or capture reference for this checkout.",
                    "You can continue checkout when ready.",
                    "Only continue through the CartBridge checkout and PayPal Sandbox approval flow.",
                    "FALLBACK"
            );
            case FAILED -> new CheckoutRecoveryAdviceResponse(
                    "Checkout could not be completed",
                    "The backend has no PayPal order or capture reference for this checkout.",
                    "You can start a new checkout after confirming no separate payment was made.",
                    "If you see a charge or pending transaction, do not try again until its status is confirmed.",
                    "FALLBACK"
            );
            case CANCELLED -> new CheckoutRecoveryAdviceResponse(
                    "Checkout was cancelled",
                    "No PayPal order or capture reference is recorded for this checkout.",
                    "You can start a new checkout if you still want to purchase.",
                    "If you see a charge or pending transaction, do not try again until its status is confirmed.",
                    "FALLBACK"
            );
            default -> new CheckoutRecoveryAdviceResponse(
                    "Payment status needs verification",
                    "The backend cannot confirm the current payment state.",
                    "Do not start or retry payment until the payment status is confirmed.",
                    "An unverified payment could be charged more than once.",
                    "FALLBACK"
            );
        };
    }

    private CheckoutRecoveryAdviceResponse currentPaymentGuidance(
            CheckoutSession session,
            CheckoutRecoveryAdviceResponse advice
    ) {
        if (session.status() == CheckoutStatus.COMPLETED
                || session.status() == CheckoutStatus.APPROVED
                || session.status() == CheckoutStatus.PENDING) {
            return deterministicStatusAdvice(session);
        }
        if (session.paypalOrderId() != null || session.paypalCaptureId() != null) {
            return fallbackAdvice(session);
        }
        if (session.status() == CheckoutStatus.FAILED || session.status() == CheckoutStatus.CANCELLED) {
            return fallbackAdvice(session);
        }
        return advice;
    }

    private boolean isValidAdvice(CheckoutRecoveryAdviceResponse advice) {
        if (advice == null
                || isBlank(advice.detectedIssue())
                || isBlank(advice.explanation())
                || isBlank(advice.recommendedNextAction())
                || advice.safetyWarning() == null) {
            return false;
        }
        String text = String.join(
                " ",
                advice.detectedIssue(),
                advice.explanation(),
                advice.recommendedNextAction(),
                advice.safetyWarning()
        );
        return !PAYMENT_SUCCESS_CLAIM.matcher(text).find()
                && !SENSITIVE_INFORMATION_REQUEST.matcher(text).find();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeWarning(String warning) {
        return isBlank(warning)
                ? "Do not share payment credentials or retry while payment status is uncertain."
                : warning;
    }
}
