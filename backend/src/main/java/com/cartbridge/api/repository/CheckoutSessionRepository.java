package com.cartbridge.api.repository;

import com.cartbridge.api.entity.CheckoutSession;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class CheckoutSessionRepository {
    private final Map<UUID, CheckoutSession> sessions = new ConcurrentHashMap<>();

    public CheckoutSession save(CheckoutSession session) {
        sessions.put(session.id(), session);
        return session;
    }

    public Optional<CheckoutSession> findById(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public List<CheckoutSession> findAll() {
        return List.copyOf(sessions.values());
    }
}
