package dev.cauce.orchestration.persistence;

import dev.cauce.orchestration.PendingInvocationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence mapping for a pending invocation row. Infrastructure detail of
 * cauce-orchestration; the domain type is
 * {@link dev.cauce.orchestration.PendingInvocation}, converted by
 * {@link PendingInvocationMapper}.
 */
@Entity
@Table(name = "pending_invocations")
public class PendingInvocationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "trigger_message_id", nullable = false, updatable = false)
    private UUID triggerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PendingInvocationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claimed_by", length = 255)
    private String claimedBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PendingInvocationEntity() {
        // for JPA
    }

    public PendingInvocationEntity(UUID id, UUID tenantId, UUID conversationId, UUID triggerMessageId,
                                   PendingInvocationStatus status, int attemptCount, int maxAttempts,
                                   Instant lastAttemptAt, String lastError, Instant createdAt,
                                   Instant claimedAt, String claimedBy, Instant completedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.triggerMessageId = triggerMessageId;
        this.status = status;
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.lastAttemptAt = lastAttemptAt;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.claimedAt = claimedAt;
        this.claimedBy = claimedBy;
        this.completedAt = completedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getTriggerMessageId() {
        return triggerMessageId;
    }

    public PendingInvocationStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
