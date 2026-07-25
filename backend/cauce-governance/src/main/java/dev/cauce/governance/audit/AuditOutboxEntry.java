package dev.cauce.governance.audit;

import dev.cauce.core.audit.AuditEvent;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One captured auditable action awaiting its move to the append-only ledger. Written by
 * {@link dev.cauce.core.audit.AuditEventRecorder} in the SAME transaction as the business fact it audits (the
 * transactional-outbox guarantee: both commit or neither does), then drained per tenant, in
 * capture order, by the background drainer.
 *
 * <p>Pure domain type: no persistence or framework dependencies.
 */
public record AuditOutboxEntry(UUID id,
                               UUID tenantId,
                               String eventType,
                               Map<String, Object> payload,
                               DrainStatus drainStatus,
                               Instant createdAt) {

    public AuditOutboxEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(drainStatus, "drainStatus must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        payload = Map.copyOf(payload);
    }

    /** Captures {@code event} as a new PENDING outbox entry, minting a UUIDv7 id. */
    public static AuditOutboxEntry create(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return new AuditOutboxEntry(UuidGenerator.newV7(), event.tenantId(), event.eventType(),
                event.payload(), DrainStatus.PENDING, Instant.now());
    }

    /** This entry after the drainer moved it to the ledger. */
    public AuditOutboxEntry drained() {
        return new AuditOutboxEntry(id, tenantId, eventType, payload, DrainStatus.DRAINED,
                createdAt);
    }
}
