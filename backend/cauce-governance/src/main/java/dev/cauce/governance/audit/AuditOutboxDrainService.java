package dev.cauce.governance.audit;

import dev.cauce.core.tenant.NoTenantContext;
import dev.cauce.governance.audit.signing.AuditEntrySigner;
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
import org.springframework.beans.factory.ObjectProvider;
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
 * {@link AuditChainHasher} under {@link AuditChainHasher#CURRENT_SCHEME} and written ON THE
 * INSERT (the ledger stays append-only — no UPDATE ever touches it); the head advances in the same transaction as the INSERTs and the
 * outbox PENDING → DRAINED flips. A drainer restart mid-run therefore re-reads the
 * still-PENDING remainder and continues both the sequence and the chain — no duplicates, no
 * gaps, no re-chaining. A tenant with no head row yet is initialized once: the sequence
 * continues from {@code MAX(sequence_number)} (respecting pre-chain rows, if any) and the
 * chain starts at the tenant-derived genesis hash. The per-tenant row lock serializes drains
 * within a tenant without blocking other tenants; {@code UNIQUE (tenant_id,
 * sequence_number)} and {@code UNIQUE (outbox_id)} remain the schema-level backstops.
 *
 * <p>Since the signing unit it also signs what it writes, when a signing key is configured:
 * the Ed25519 signature over the entry hash, plus the {@code key_id} and signature scheme, go
 * in on the same INSERT. Without a key the same INSERT happens with those three columns null,
 * and verification reports those entries as unsigned rather than as broken.
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
    private final ObjectProvider<AuditEntrySigner> signerProvider;

    public AuditOutboxDrainService(AuditOutboxEntryRepository outboxRepository,
                                   AuditOutboxEntryMapper outboxMapper,
                                   AuditLogEntryRepository logRepository,
                                   AuditLogEntryMapper logMapper,
                                   AuditChainHeadRepository headRepository,
                                   AuditChainHeadMapper headMapper,
                                   AuditChainHasher hasher,
                                   ObjectProvider<AuditEntrySigner> signerProvider) {
        this.outboxRepository = outboxRepository;
        this.outboxMapper = outboxMapper;
        this.logRepository = logRepository;
        this.logMapper = logMapper;
        this.headRepository = headRepository;
        this.headMapper = headMapper;
        this.hasher = hasher;
        this.signerProvider = signerProvider;
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
        // Resolved once per batch: absent when no signing key is configured, which is a
        // supported configuration — the entries are simply written unsigned.
        AuditEntrySigner signer = signerProvider.getIfAvailable();
        for (AuditOutboxEntryEntity entity : pending) {
            AuditOutboxEntry entry = outboxMapper.toDomain(entity);
            // Both minted BEFORE hashing: the v2 preimage commits to the entry id, and the
            // timestamp must be the one that will be stored (see AuditLogEntry).
            UUID entryId = AuditLogEntry.mintId();
            Instant drainedAt = AuditLogEntry.mintDrainedAt();
            String scheme = AuditChainHasher.CURRENT_SCHEME;
            String payloadHash = hasher.payloadHash(scheme, entry.payload());
            String entryHash = hasher.entryHash(scheme, entryId, tenantId, nextSequence,
                    entry.id(), entry.eventType(), drainedAt, payloadHash, prevHash);
            // Signed AFTER the entry hash, over that hash: the signature commits to the whole
            // chain prefix without the signing layer knowing how entries are hashed. A signing
            // failure propagates and fails the batch — storing the entry unsigned instead
            // would be a silent downgrade of a configured guarantee.
            String signature = signer == null ? null : signer.sign(entryHash);
            String keyId = signer == null ? null : signer.keyId();
            String signatureScheme = signer == null ? null : signer.signatureScheme();
            logRepository.save(logMapper.toEntity(AuditLogEntry.chained(entryId, entry,
                    nextSequence, drainedAt, payloadHash, prevHash, entryHash, scheme,
                    signature, keyId, signatureScheme)));
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
