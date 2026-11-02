package com.cartbridge.api.repository;

import com.cartbridge.api.entity.CheckoutRecoveryHistoryEntry;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Repository
public class CheckoutRecoveryHistoryRepository {
    private final ConcurrentLinkedDeque<CheckoutRecoveryHistoryEntry> history =
            new ConcurrentLinkedDeque<>();

    public CheckoutRecoveryHistoryEntry save(CheckoutRecoveryHistoryEntry entry) {
        history.addFirst(entry);
        return entry;
    }

    public List<CheckoutRecoveryHistoryEntry> findAll() {
        return List.copyOf(history);
    }
}
