package dev.cauce.memory.conversation;

import dev.cauce.core.conversation.ConversationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence mapping for a conversation row. Infrastructure detail of cauce-memory;
 * the domain type is {@link dev.cauce.core.conversation.Conversation}, converted by
 * {@link ConversationMapper}.
 */
@Entity
@Table(name = "conversations")
public class ConversationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "channel_type", nullable = false, length = 20)
    private String channelType;

    @Column(name = "external_identity_ref", nullable = false, length = 320)
    private String externalIdentityRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ConversationStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected ConversationEntity() {
        // for JPA
    }

    public ConversationEntity(UUID id, UUID agentId, String channelType, String externalIdentityRef,
                              ConversationStatus status, Instant startedAt, Instant lastMessageAt,
                              Instant closedAt) {
        this.id = id;
        this.agentId = agentId;
        this.channelType = channelType;
        this.externalIdentityRef = externalIdentityRef;
        this.status = status;
        this.startedAt = startedAt;
        this.lastMessageAt = lastMessageAt;
        this.closedAt = closedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getChannelType() {
        return channelType;
    }

    public String getExternalIdentityRef() {
        return externalIdentityRef;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
