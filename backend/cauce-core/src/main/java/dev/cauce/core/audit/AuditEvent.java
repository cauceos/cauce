package dev.cauce.core.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One auditable action as its business call site reports it: the owning tenant, an event
 * type, and an opaque payload document. The caller hands this to {@link AuditEventRecorder}
 * inside its own transaction; capture, ordering, and the ledger are the audit adapter's
 * concern (cauce-governance).
 *
 * <p>The payload must contain NON-SENSITIVE metadata only — never raw message text or any
 * erasable personal data. Content is bound by hash instead ({@link AuditContentHash}):
 * erasable content lives in the mutable business tables, where deletion operates; the
 * append-only audit ledger keeps only its hash. The payload is copied defensively and
 * exposed immutable.
 */
public record AuditEvent(UUID tenantId, String eventType, Map<String, Object> payload) {

    public AuditEvent {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        payload = Map.copyOf(payload);
    }
}
