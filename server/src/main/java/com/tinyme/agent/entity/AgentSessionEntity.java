package com.tinyme.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "agent_sessions")
public class AgentSessionEntity {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "anthropic_session_id", nullable = false, unique = true)
    private String anthropicSessionId;

    @Column(nullable = false)
    private String agent;

    @Column(name = "agent_version", nullable = false)
    private int agentVersion;

    @Column(nullable = false)
    private String kind;

    @Column(name = "local_day")
    private LocalDate localDay;

    @Column(nullable = false)
    private String status;

    protected AgentSessionEntity() {
    }

    public static AgentSessionEntity chat(String anthropicSessionId, int agentVersion, LocalDate localDay) {
        var session = new AgentSessionEntity();
        session.anthropicSessionId = anthropicSessionId;
        session.agent = "chat";
        session.agentVersion = agentVersion;
        session.kind = "chat";
        session.localDay = localDay;
        session.status = "active";
        return session;
    }

    public UUID getId() {
        return id;
    }

    public String getAnthropicSessionId() {
        return anthropicSessionId;
    }
}
