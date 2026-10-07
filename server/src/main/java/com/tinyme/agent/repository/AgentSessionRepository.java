package com.tinyme.agent.repository;

import com.tinyme.agent.entity.AgentSessionEntity;
import com.tinyme.agent.model.SessionRef;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

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

    // A duplicate insert must finish rolling back before the caller reads the winning row.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SessionRef insertChat(String anthropicSessionId, int agentVersion, LocalDate day) {
        return reference(sessions.saveAndFlush(AgentSessionEntity.chat(anthropicSessionId, agentVersion, day)));
    }

    private static SessionRef reference(AgentSessionEntity session) {
        return new SessionRef(session.getId(), session.getAnthropicSessionId());
    }
}
