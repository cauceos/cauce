package dev.cauce.business.handoff;

import dev.cauce.core.business.handoff.Handoff;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a {@code handoffs} row (V37, V42). */
@Entity
@Table(name = "handoffs")
public class HandoffEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "code", nullable = false, updatable = false, length = 12)
    private String code;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "identity_id", nullable = false, updatable = false)
    private UUID identityId;

    @Column(name = "category", nullable = false, updatable = false, length = 40)
    private String category;

    @Column(name = "summary", nullable = false, updatable = false, length = 2000)
    private String summary;

    @Column(name = "contact_name", nullable = false, updatable = false, length = 100)
    private String contactName;

    @Column(name = "contact_phone", updatable = false, length = 40)
    private String contactPhone;

    @Column(name = "customer_message", updatable = false, length = 1000)
    private String customerMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Handoff.Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected HandoffEntity() {
        // for JPA
    }

    public HandoffEntity(UUID id, UUID tenantId, String code, UUID agentId, UUID conversationId, UUID identityId,
                         String category, String summary, String contactName, String contactPhone,
                         String customerMessage, Handoff.Status status, Instant createdAt, Instant resolvedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.identityId = identityId;
        this.category = category;
        this.summary = summary;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.customerMessage = customerMessage;
        this.status = status;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getIdentityId() {
        return identityId;
    }

    public String getCategory() {
        return category;
    }

    public String getSummary() {
        return summary;
    }

    public String getContactName() {
        return contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getCustomerMessage() {
        return customerMessage;
    }

    public Handoff.Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
