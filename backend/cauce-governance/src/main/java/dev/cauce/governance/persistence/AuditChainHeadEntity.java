package dev.cauce.governance.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence mapping for one tenant's chain head (V23). Infrastructure detail of
 * cauce-governance; the domain type is {@link dev.cauce.governance.audit.AuditChainHead},
 * converted by {@link AuditChainHeadMapper}. Deliberately the one mutable governance row:
 * the drainer advances it in the same transaction as the ledger INSERTs it belongs to.
 */
@Entity
@Table(name = "audit_chain_heads")
public class AuditChainHeadEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "last_sequence_number", nullable = false)
    private long lastSequenceNumber;

    @Column(name = "last_entry_hash", nullable = false, length = 128)
    private String lastEntryHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AuditChainHeadEntity() {
        // for JPA
    }

    public AuditChainHeadEntity(UUID tenantId, long lastSequenceNumber, String lastEntryHash,
                                Instant updatedAt) {
        this.tenantId = tenantId;
        this.lastSequenceNumber = lastSequenceNumber;
        this.lastEntryHash = lastEntryHash;
        this.updatedAt = updatedAt;
    }

    /** Advances this head to the last entry the current drain batch appended. */
    public void advanceTo(long lastSequenceNumber, String lastEntryHash, Instant updatedAt) {
        this.lastSequenceNumber = lastSequenceNumber;
        this.lastEntryHash = lastEntryHash;
        this.updatedAt = updatedAt;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public long getLastSequenceNumber() {
        return lastSequenceNumber;
    }

    public String getLastEntryHash() {
        return lastEntryHash;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
