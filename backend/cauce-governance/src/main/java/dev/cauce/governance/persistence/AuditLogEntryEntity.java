package dev.cauce.governance.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA persistence mapping for one append-only audit ledger row. Infrastructure detail of
 * cauce-governance; the domain type is {@link dev.cauce.governance.audit.AuditLogEntry},
 * converted by {@link AuditLogEntryMapper}. Insert-only: every column is
 * {@code updatable = false}, and the database revokes UPDATE/DELETE from the runtime role
 * anyway (V21) — the annotation mirrors the grant, it does not implement the guarantee.
 * The hash/signature columns are reserved for the chain unit and stay null here.
 */
@Entity
@Table(name = "audit_log_entries")
public class AuditLogEntryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private long sequenceNumber;

    @Column(name = "outbox_id", nullable = false, updatable = false)
    private UUID outboxId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private Map<String, Object> payload;

    @Column(name = "drained_at", nullable = false, updatable = false)
    private Instant drainedAt;

    @Column(name = "prev_hash", updatable = false, length = 128)
    private String prevHash;

    @Column(name = "entry_hash", updatable = false, length = 128)
    private String entryHash;

    @Column(name = "signature", updatable = false)
    private String signature;

    protected AuditLogEntryEntity() {
        // for JPA
    }

    public AuditLogEntryEntity(UUID id, UUID tenantId, long sequenceNumber, UUID outboxId,
                               String eventType, Map<String, Object> payload, Instant drainedAt,
                               String prevHash, String entryHash, String signature) {
        this.id = id;
        this.tenantId = tenantId;
        this.sequenceNumber = sequenceNumber;
        this.outboxId = outboxId;
        this.eventType = eventType;
        this.payload = payload;
        this.drainedAt = drainedAt;
        this.prevHash = prevHash;
        this.entryHash = entryHash;
        this.signature = signature;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public UUID getOutboxId() {
        return outboxId;
    }

    public String getEventType() {
        return eventType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getDrainedAt() {
        return drainedAt;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getEntryHash() {
        return entryHash;
    }

    public String getSignature() {
        return signature;
    }
}
