package dev.cauce.governance.persistence;

import dev.cauce.governance.audit.DrainStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA persistence mapping for one audit outbox row. Infrastructure detail of
 * cauce-governance; the domain type is {@link dev.cauce.governance.audit.AuditOutboxEntry},
 * converted by {@link AuditOutboxEntryMapper}. Only {@code drain_status} is mutable (the
 * drainer's PENDING → DRAINED flip).
 */
@Entity
@Table(name = "audit_outbox")
public class AuditOutboxEntryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "drain_status", nullable = false, length = 20)
    private DrainStatus drainStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditOutboxEntryEntity() {
        // for JPA
    }

    public AuditOutboxEntryEntity(UUID id, UUID tenantId, String eventType,
                                  Map<String, Object> payload, DrainStatus drainStatus,
                                  Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.payload = payload;
        this.drainStatus = drainStatus;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEventType() {
        return eventType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public DrainStatus getDrainStatus() {
        return drainStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
