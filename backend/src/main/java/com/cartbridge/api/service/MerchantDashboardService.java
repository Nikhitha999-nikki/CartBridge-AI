package com.cartbridge.api.service;

import com.cartbridge.api.dto.CheckoutSessionResponse;
import com.cartbridge.api.dto.MerchantDashboardResponse;
import com.cartbridge.api.entity.CheckoutRecoveryHistoryEntry;
import com.cartbridge.api.entity.CheckoutSession;
import com.cartbridge.api.entity.CheckoutStatus;
import com.cartbridge.api.repository.CheckoutRecoveryHistoryRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class MerchantDashboardService {
    private final CheckoutSessionService checkoutSessionService;
    private final CheckoutRecoveryHistoryRepository recoveryHistoryRepository;

    public MerchantDashboardService(
            CheckoutSessionService checkoutSessionService,
            CheckoutRecoveryHistoryRepository recoveryHistoryRepository
    ) {
        this.checkoutSessionService = checkoutSessionService;
        this.recoveryHistoryRepository = recoveryHistoryRepository;
    }

    public MerchantDashboardResponse getDashboard() {
        List<CheckoutSession> sessions = checkoutSessionService.findAll().stream()
                .sorted(Comparator.comparing(CheckoutSession::createdAt).reversed())
                .toList();
        Map<CheckoutStatus, Long> statusCounts = new EnumMap<>(CheckoutStatus.class);
        for (CheckoutStatus status : CheckoutStatus.values()) {
            statusCounts.put(status, 0L);
        }
        for (CheckoutSession session : sessions) {
            statusCounts.compute(session.status(), (status, count) -> count + 1);
        }

        return new MerchantDashboardResponse(
                sessions.size(),
                Map.copyOf(statusCounts),
                sessions.stream().map(CheckoutSessionResponse::from).toList()
        );
    }

    public List<CheckoutRecoveryHistoryEntry> getRecoveryHistory() {
        return recoveryHistoryRepository.findAll();
    }
}
