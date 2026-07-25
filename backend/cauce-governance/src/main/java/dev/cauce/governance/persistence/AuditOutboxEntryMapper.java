package dev.cauce.governance.persistence;

import dev.cauce.governance.audit.AuditOutboxEntry;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link AuditOutboxEntry} and its JPA
 * {@link AuditOutboxEntryEntity}. No external mapping library is used.
 */
@Component
public final class AuditOutboxEntryMapper {

    public AuditOutboxEntryEntity toEntity(AuditOutboxEntry entry) {
        return new AuditOutboxEntryEntity(
                entry.id(),
                entry.tenantId(),
                entry.eventType(),
                entry.payload(),
                entry.drainStatus(),
                entry.createdAt());
    }

    public AuditOutboxEntry toDomain(AuditOutboxEntryEntity entity) {
        return new AuditOutboxEntry(
                entity.getId(),
                entity.getTenantId(),
                entity.getEventType(),
                entity.getPayload(),
                entity.getDrainStatus(),
                entity.getCreatedAt());
    }
}
