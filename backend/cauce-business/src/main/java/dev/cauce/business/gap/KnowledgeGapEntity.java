package dev.cauce.business.gap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a {@code knowledge_gaps} row (V35). */
@Entity
@Table(name = "knowledge_gaps")
public class KnowledgeGapEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "question", nullable = false, updatable = false)
    private String question;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KnowledgeGapEntity() {
        // for JPA
    }

    public KnowledgeGapEntity(UUID id, UUID tenantId, UUID agentId, UUID conversationId, String question,
                              Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.question = question;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getQuestion() {
        return question;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
