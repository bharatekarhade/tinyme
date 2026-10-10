package com.tinyme.agent.service;

import com.tinyme.agent.client.ManagedAgents;
import com.tinyme.agent.model.SessionRef;
import com.tinyme.agent.repository.AgentSessionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SessionManager {
    private final AgentSessionRepository sessions;
    private final AgentResources resources;
    private final ManagedAgents api;
    private final Clock clock;

    SessionManager(AgentSessionRepository sessions, AgentResources resources, ManagedAgents api, Clock clock) {
        this.sessions = sessions;
        this.resources = resources;
        this.api = api;
        this.clock = clock;
    }

    // Keep HTTP outside any caller's transaction and let each repository operation commit independently.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SessionRef todaySession(ZoneId zone) throws IOException, InterruptedException {
        Objects.requireNonNull(zone, "zone");
        LocalDate today = LocalDate.now(clock.withZone(zone));
        var existing = sessions.findActiveChat(today);
        if (existing.isPresent()) return existing.get();

        var ids = resources.load();
        String sessionId = api.createSession(Map.of(
                "agent", Map.of("type", "agent", "id", ids.agentId(), "version", ids.agentVersion()),
                "environment_id", ids.environmentId(),
                "resources", List.of(Map.of(
                        "type", "memory_store",
                        "memory_store_id", ids.memoryStoreId(),
                        "access", "read_write",
                        "instructions", "Read me/profile.md, me/preferences.md and me/rules.md relative to this store's mount_path. "
                                + "Keep durable user facts up to date; update existing facts instead of duplicating them."))));
        try {
            return sessions.insertChat(sessionId, ids.agentVersion(), today);
        } catch (DataIntegrityViolationException conflict) {
            if (!isDailySessionConflict(conflict)) throw conflict;
            // Both calls may have created a remote session; only the database winner is used.
            return sessions.findActiveChat(today).orElseThrow(() -> conflict);
        }
    }

    private static boolean isDailySessionConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "one_active_chat_session_per_day".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
