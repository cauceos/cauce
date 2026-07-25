package dev.cauce.governance.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One auditable action as its business call site reports it: the owning tenant, an event
 * type, and an opaque payload document. The caller hands this to {@link AuditEventRecorder}
 * inside its own transaction; capture, ordering, and the ledger are governance's concern.
 *
 * <p>{@code eventType} carries no semantics yet — the real auditable vocabulary is a
 * follow-up unit (this unit is the capture container). The payload is copied defensively and
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
