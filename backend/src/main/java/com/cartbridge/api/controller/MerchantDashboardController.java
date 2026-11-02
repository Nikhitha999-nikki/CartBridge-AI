package com.cartbridge.api.controller;

import com.cartbridge.api.dto.MerchantDashboardResponse;
import com.cartbridge.api.entity.CheckoutRecoveryHistoryEntry;
import com.cartbridge.api.service.MerchantDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/merchant")
public class MerchantDashboardController {
    private final MerchantDashboardService merchantDashboardService;

    public MerchantDashboardController(MerchantDashboardService merchantDashboardService) {
        this.merchantDashboardService = merchantDashboardService;
    }

    @GetMapping("/dashboard")
    public MerchantDashboardResponse dashboard() {
        return merchantDashboardService.getDashboard();
    }

    @GetMapping("/recovery-history")
    public List<CheckoutRecoveryHistoryEntry> recoveryHistory() {
        return merchantDashboardService.getRecoveryHistory();
    }
}
