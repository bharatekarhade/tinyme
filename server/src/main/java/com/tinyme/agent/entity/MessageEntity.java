package com.tinyme.agent.entity;

import com.tinyme.agent.model.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class MessageEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AgentSessionEntity session;

    @Column(name = "client_msg_id", unique = true)
    private UUID clientMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private MessageRole role;

    @Column(name = "content", nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions", nullable = false)
    private List<Object> actions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", nullable = false)
    private List<Object> attachments = new ArrayList<>();

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected MessageEntity() {
    }

    public static MessageEntity create(AgentSessionEntity session, MessageRole role, String content) {
        return create(session, role, content, List.of());
    }

    public static MessageEntity create(AgentSessionEntity session, MessageRole role, String content,
                                       List<?> actions) {
        return create(session, role, content, actions, null);
    }

    public static MessageEntity create(AgentSessionEntity session, MessageRole role, String content,
                                       List<?> actions, UUID clientMessageId) {
        var message = new MessageEntity();
        message.session = Objects.requireNonNull(session, "session");
        message.clientMessageId = clientMessageId;
        message.role = Objects.requireNonNull(role, "role");
        message.content = Objects.requireNonNull(content, "content");
        message.actions = new ArrayList<>(Objects.requireNonNull(actions, "actions"));
        return message;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return session.getId();
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public List<Object> getActions() {
        return List.copyOf(actions);
    }

    public List<Object> getAttachments() {
        return List.copyOf(attachments);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
