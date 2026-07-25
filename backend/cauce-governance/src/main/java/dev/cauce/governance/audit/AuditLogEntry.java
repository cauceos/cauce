package dev.cauce.governance.audit;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of the append-only audit ledger: a drained outbox row plus its per-tenant,
 * contiguous {@code sequenceNumber} (starting at 1). Entries are immutable facts — the
 * database enforces it (UPDATE/DELETE are revoked from the runtime role), the domain type
 * merely mirrors it.
 *
 * <p>{@code prevHash}, {@code entryHash}, and {@code signature} are RESERVED for the
 * hash-chain unit: this unit never fills them (always null here), but the ledger shape is
 * final so the chain lands additively.
 *
 * <p>Pure domain type: no persistence or framework dependencies.
 */
public record AuditLogEntry(UUID id,
                            UUID tenantId,
                            long sequenceNumber,
                            UUID outboxId,
                            String eventType,
                            Map<String, Object> payload,
                            Instant drainedAt,
                            String prevHash,
                            String entryHash,
                            String signature) {

    public AuditLogEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(outboxId, "outboxId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(drainedAt, "drainedAt must not be null");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        payload = Map.copyOf(payload);
    }

    /**
     * Numbers {@code outboxEntry} into the ledger as sequence {@code sequenceNumber} of its
     * tenant. The chain columns stay null — reserved for the hash-chain unit.
     */
    public static AuditLogEntry fromOutbox(AuditOutboxEntry outboxEntry, long sequenceNumber) {
        Objects.requireNonNull(outboxEntry, "outboxEntry must not be null");
        return new AuditLogEntry(UuidGenerator.newV7(), outboxEntry.tenantId(), sequenceNumber,
                outboxEntry.id(), outboxEntry.eventType(), outboxEntry.payload(), Instant.now(),
                null, null, null);
    }
}
