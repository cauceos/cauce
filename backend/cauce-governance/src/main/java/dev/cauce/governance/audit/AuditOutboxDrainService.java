package dev.cauce.governance.audit;

import dev.cauce.core.tenant.NoTenantContext;
import dev.cauce.governance.persistence.AuditChainHeadEntity;
import dev.cauce.governance.persistence.AuditChainHeadMapper;
import dev.cauce.governance.persistence.AuditChainHeadRepository;
import dev.cauce.governance.persistence.AuditLogEntryMapper;
import dev.cauce.governance.persistence.AuditLogEntryRepository;
import dev.cauce.governance.persistence.AuditOutboxEntryEntity;
import dev.cauce.governance.persistence.AuditOutboxEntryMapper;
import dev.cauce.governance.persistence.AuditOutboxEntryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * <p>{@code drainBatch} is one transaction per tenant batch, and since the chain unit it
 * hash-chains what it moves: the tenant's head row (V23, locked {@code FOR UPDATE}) yields
 * the next sequence number AND the previous entry hash in one consistent read; each drained
 * row gets {@code payload_hash}, {@code prev_hash}, and {@code entry_hash} computed by
 * {@link AuditChainHasher} and written ON THE INSERT (the ledger stays append-only — no
 * UPDATE ever touches it); the head advances in the same transaction as the INSERTs and the
 * outbox PENDING → DRAINED flips. A drainer restart mid-run therefore re-reads the
 * still-PENDING remainder and continues both the sequence and the chain — no duplicates, no
 * gaps, no re-chaining. A tenant with no head row yet is initialized once: the sequence
 * continues from {@code MAX(sequence_number)} (respecting pre-chain rows, if any) and the
 * chain starts at the tenant-derived genesis hash. The per-tenant row lock serializes drains
 * within a tenant without blocking other tenants; {@code UNIQUE (tenant_id,
 * sequence_number)} and {@code UNIQUE (outbox_id)} remain the schema-level backstops.
 */
@Service
public class AuditOutboxDrainService {

    private final AuditOutboxEntryRepository outboxRepository;
    private final AuditOutboxEntryMapper outboxMapper;
    private final AuditLogEntryRepository logRepository;
    private final AuditLogEntryMapper logMapper;
    private final AuditChainHeadRepository headRepository;
    private final AuditChainHeadMapper headMapper;
    private final AuditChainHasher hasher;

    public AuditOutboxDrainService(AuditOutboxEntryRepository outboxRepository,
                                   AuditOutboxEntryMapper outboxMapper,
                                   AuditLogEntryRepository logRepository,
                                   AuditLogEntryMapper logMapper,
                                   AuditChainHeadRepository headRepository,
                                   AuditChainHeadMapper headMapper,
                                   AuditChainHasher hasher) {
        this.outboxRepository = outboxRepository;
        this.outboxMapper = outboxMapper;
        this.logRepository = logRepository;
        this.logMapper = logMapper;
        this.headRepository = headRepository;
        this.headMapper = headMapper;
        this.hasher = hasher;
    }

    /** Tenants with PENDING outbox rows, via the cross-tenant escape hatch (ADR 0001). */
    @Transactional(readOnly = true)
    @NoTenantContext
    public List<UUID> pendingTenants() {
        return outboxRepository.pendingTenants();
    }

    /**
     * Drains up to {@code batchSize} of {@code tenantId}'s oldest PENDING rows into the
     * ledger, chained. Requires the tenant's {@code TenantContext} to be set by the caller.
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
        // One consistent, locked read gives BOTH the next sequence and the next prev_hash.
        Optional<AuditChainHeadEntity> head = headRepository.findByTenantId(tenantId);
        long nextSequence;
        String prevHash;
        if (head.isPresent()) {
            nextSequence = head.get().getLastSequenceNumber() + 1;
            prevHash = head.get().getLastEntryHash();
        } else {
            // One-time initialization: continue past any pre-chain rows, start at genesis.
            nextSequence = logRepository.findMaxSequenceNumber(tenantId).orElse(0L) + 1;
            prevHash = hasher.genesisHash(tenantId);
        }
        for (AuditOutboxEntryEntity entity : pending) {
            AuditOutboxEntry entry = outboxMapper.toDomain(entity);
            Instant drainedAt = AuditLogEntry.mintDrainedAt();
            String payloadHash = hasher.payloadHash(entry.payload());
            String entryHash = hasher.entryHash(tenantId, nextSequence, entry.id(),
                    entry.eventType(), drainedAt, payloadHash, prevHash);
            logRepository.save(logMapper.toEntity(AuditLogEntry.chained(entry, nextSequence,
                    drainedAt, payloadHash, prevHash, entryHash, AuditChainHasher.SCHEME)));
            outboxRepository.save(outboxMapper.toEntity(entry.drained()));
            prevHash = entryHash;
            nextSequence++;
        }
        long lastSequence = nextSequence - 1;
        if (head.isPresent()) {
            head.get().advanceTo(lastSequence, prevHash, Instant.now());
        } else {
            headRepository.save(headMapper.toEntity(
                    AuditChainHead.of(tenantId, lastSequence, prevHash)));
        }
        return pending.size();
    }
}
