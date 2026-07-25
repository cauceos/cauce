package dev.cauce.governance.audit;

import dev.cauce.core.tenant.NoTenantContext;
import dev.cauce.governance.persistence.AuditLogEntryMapper;
import dev.cauce.governance.persistence.AuditLogEntryRepository;
import dev.cauce.governance.persistence.AuditOutboxEntryEntity;
import dev.cauce.governance.persistence.AuditOutboxEntryMapper;
import dev.cauce.governance.persistence.AuditOutboxEntryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional units of the audit drain. {@link #pendingTenants()} is the only cross-tenant
 * step ({@code @NoTenantContext}, backed by the V20 SECURITY DEFINER function returning
 * tenant ids only); {@link #drainBatch} runs under the drained tenant's context set by the
 * caller, so every row read and write is RLS-scoped.
 *
 * <p>{@code drainBatch} is one transaction per tenant batch: ledger INSERTs (assigning the
 * per-tenant contiguous sequence from {@code MAX(sequence_number) + 1}) and the outbox
 * PENDING → DRAINED flips commit or roll back together. A drainer restart mid-run therefore
 * re-reads the still-PENDING remainder and continues the sequence — no duplicates, no gaps.
 * Correctness under concurrent drains of the same tenant is anchored in the schema, not
 * here: {@code UNIQUE (tenant_id, sequence_number)} and {@code UNIQUE (outbox_id)} turn a
 * race into a constraint violation and a retried batch.
 */
@Service
public class AuditOutboxDrainService {

    private final AuditOutboxEntryRepository outboxRepository;
    private final AuditOutboxEntryMapper outboxMapper;
    private final AuditLogEntryRepository logRepository;
    private final AuditLogEntryMapper logMapper;

    public AuditOutboxDrainService(AuditOutboxEntryRepository outboxRepository,
                                   AuditOutboxEntryMapper outboxMapper,
                                   AuditLogEntryRepository logRepository,
                                   AuditLogEntryMapper logMapper) {
        this.outboxRepository = outboxRepository;
        this.outboxMapper = outboxMapper;
        this.logRepository = logRepository;
        this.logMapper = logMapper;
    }

    /** Tenants with PENDING outbox rows, via the cross-tenant escape hatch (ADR 0001). */
    @Transactional(readOnly = true)
    @NoTenantContext
    public List<UUID> pendingTenants() {
        return outboxRepository.pendingTenants();
    }

    /**
     * Drains up to {@code batchSize} of {@code tenantId}'s oldest PENDING rows into the
     * ledger. Requires the tenant's {@code TenantContext} to be set by the caller.
     *
     * @return the number of rows drained (0 when nothing is pending)
     */
    @Transactional
    public int drainBatch(UUID tenantId, int batchSize) {
        List<AuditOutboxEntryEntity> pending =
                outboxRepository.findByTenantIdAndDrainStatusOrderByCreatedAtAscIdAsc(
                        tenantId, DrainStatus.PENDING, Limit.of(batchSize));
        if (pending.isEmpty()) {
            return 0;
        }
        long nextSequence = logRepository.findMaxSequenceNumber(tenantId).orElse(0L) + 1;
        for (AuditOutboxEntryEntity entity : pending) {
            AuditOutboxEntry entry = outboxMapper.toDomain(entity);
            logRepository.save(logMapper.toEntity(AuditLogEntry.fromOutbox(entry, nextSequence++)));
            outboxRepository.save(outboxMapper.toEntity(entry.drained()));
        }
        return pending.size();
    }
}
