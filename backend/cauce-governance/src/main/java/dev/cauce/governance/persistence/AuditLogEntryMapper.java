package dev.cauce.governance.persistence;

import dev.cauce.governance.audit.AuditLogEntry;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link AuditLogEntry} and its JPA
 * {@link AuditLogEntryEntity}. No external mapping library is used.
 */
@Component
public final class AuditLogEntryMapper {

    public AuditLogEntryEntity toEntity(AuditLogEntry entry) {
        return new AuditLogEntryEntity(
                entry.id(),
                entry.tenantId(),
                entry.sequenceNumber(),
                entry.outboxId(),
                entry.eventType(),
                entry.payload(),
                entry.drainedAt(),
                entry.prevHash(),
                entry.entryHash(),
                entry.signature());
    }

    public AuditLogEntry toDomain(AuditLogEntryEntity entity) {
        return new AuditLogEntry(
                entity.getId(),
                entity.getTenantId(),
                entity.getSequenceNumber(),
                entity.getOutboxId(),
                entity.getEventType(),
                entity.getPayload(),
                entity.getDrainedAt(),
                entity.getPrevHash(),
                entity.getEntryHash(),
                entity.getSignature());
    }
}
