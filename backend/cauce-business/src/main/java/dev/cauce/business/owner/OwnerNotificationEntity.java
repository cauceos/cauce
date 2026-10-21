package dev.cauce.business.owner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of an {@code owner_notifications} row (V37). */
@Entity
@Table(name = "owner_notifications")
public class OwnerNotificationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 30)
    private OwnerNotification.Kind kind;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OwnerNotification.Status status;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OwnerNotificationEntity() {
        // for JPA
    }

    public OwnerNotificationEntity(UUID id, UUID tenantId, OwnerNotification.Kind kind, UUID subjectId,
                                   String content, OwnerNotification.Status status, String reason, int attempts,
                                   Instant nextAttemptAt, Instant sentAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.kind = kind;
        this.subjectId = subjectId;
        this.content = content;
        this.status = status;
        this.reason = reason;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.sentAt = sentAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public OwnerNotification.Kind getKind() {
        return kind;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getContent() {
        return content;
    }

    public OwnerNotification.Status getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
