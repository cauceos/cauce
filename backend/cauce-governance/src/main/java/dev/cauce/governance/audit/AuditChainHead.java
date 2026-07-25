package dev.cauce.governance.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The single source of a tenant's chain head: the last assigned sequence number and its
 * entry hash, read together (one consistent row, locked FOR UPDATE) by the drainer so the
 * next sequence number and the next {@code prevHash} can never diverge, and advanced in the
 * same transaction as the ledger INSERTs. Mutable operational state of the drainer — NOT
 * audit data; the ledger's integrity is protected by the chain itself.
 *
 * <p>Pure domain type: no persistence or framework dependencies.
 */
public record AuditChainHead(UUID tenantId,
                             long lastSequenceNumber,
                             String lastEntryHash,
                             Instant updatedAt) {

    public AuditChainHead {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(lastEntryHash, "lastEntryHash must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (lastSequenceNumber < 1) {
            throw new IllegalArgumentException("lastSequenceNumber must be >= 1");
        }
        if (lastEntryHash.isBlank()) {
            throw new IllegalArgumentException("lastEntryHash must not be blank");
        }
    }

    /** The head of a tenant whose first chained entry was just written. */
    public static AuditChainHead of(UUID tenantId, long lastSequenceNumber,
                                    String lastEntryHash) {
        return new AuditChainHead(tenantId, lastSequenceNumber, lastEntryHash, Instant.now());
    }

    /** This head after the drainer appended entries up to {@code lastSequenceNumber}. */
    public AuditChainHead advancedTo(long lastSequenceNumber, String lastEntryHash) {
        return new AuditChainHead(tenantId, lastSequenceNumber, lastEntryHash, Instant.now());
    }
}
