package com.tinyme.agent.repository;

import com.tinyme.agent.entity.AgentSessionEntity;
import com.tinyme.agent.model.SessionRef;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentSessionRepository {
    private final AgentSessionJpaRepository sessions;

    AgentSessionRepository(AgentSessionJpaRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<SessionRef> findActiveChat(LocalDate day) {
        return sessions.findByKindAndStatusAndLocalDay("chat", "active", day)
                .map(AgentSessionRepository::reference);
    }

    @Transactional(readOnly = true)
    public List<UUID> findChatSessionIds(LocalDate day) {
        return sessions.findByKindAndLocalDayAndStatusNotOrderByIdAsc("chat", day, "deleted").stream()
                .map(AgentSessionEntity::getId)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> findAllAnthropicSessionIds() {
        return sessions.findAllByOrderByIdAsc().stream()
                .map(AgentSessionEntity::getAnthropicSessionId)
                .toList();
    }

    // A duplicate insert must finish rolling back before the caller reads the winning row.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SessionRef insertChat(String anthropicSessionId, int agentVersion, LocalDate day) {
        return reference(sessions.saveAndFlush(AgentSessionEntity.chat(anthropicSessionId, agentVersion, day)));
    }

    /** Ends a stale-version session and inserts its replacement atomically. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SessionRef replaceChat(UUID oldSessionRowId, String anthropicSessionId, int agentVersion,
                                  LocalDate day, Instant now) {
        AgentSessionEntity old = sessions.findByIdAndKindAndLocalDay(oldSessionRowId, "chat", day)
                .orElseThrow(() -> new IllegalStateException("Active chat session disappeared during replacement"));
        if (!"active".equals(old.getStatus())) {
            return sessions.findByKindAndStatusAndLocalDay("chat", "active", day)
                    .map(AgentSessionRepository::reference)
                    .orElseThrow(() -> new IllegalStateException("No active chat session after replacement"));
        }
        old.end(now);
        sessions.saveAndFlush(old);
        return reference(sessions.saveAndFlush(AgentSessionEntity.chat(anthropicSessionId, agentVersion, day)));
    }

    private static SessionRef reference(AgentSessionEntity session) {
        return new SessionRef(session.getId(), session.getAnthropicSessionId(), session.getAgentVersion());
    }
}
