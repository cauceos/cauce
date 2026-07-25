package dev.cauce.governance.persistence;

import dev.cauce.governance.audit.AuditChainHead;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link AuditChainHead} and its JPA
 * {@link AuditChainHeadEntity}. No external mapping library is used.
 */
@Component
public final class AuditChainHeadMapper {

    public AuditChainHeadEntity toEntity(AuditChainHead head) {
        return new AuditChainHeadEntity(
                head.tenantId(),
                head.lastSequenceNumber(),
                head.lastEntryHash(),
                head.updatedAt());
    }

    public AuditChainHead toDomain(AuditChainHeadEntity entity) {
        return new AuditChainHead(
                entity.getTenantId(),
                entity.getLastSequenceNumber(),
                entity.getLastEntryHash(),
                entity.getUpdatedAt());
    }
}
