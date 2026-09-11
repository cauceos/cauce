package dev.cauce.governance.audit;

import dev.cauce.governance.persistence.AuditLogEntryEntity;
import dev.cauce.governance.persistence.AuditLogEntryMapper;
import dev.cauce.governance.persistence.AuditLogEntryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes one tenant's audit chain from the stored rows and reports either "valid" or
 * the exact sequence number of the first break. Internal service — the public verification
 * surface (endpoint, compliance report) is a follow-up unit.
 *
 * <p>Every check is performed under the scheme the row itself records ({@code hash_scheme}),
 * so entries written under v1 keep verifying under v1 rules and a chain that mixes schemes —
 * legal, and expected while instances of different versions write it — verifies end to end.
 * An unrecognized scheme is reported as a malformed entry; distinguishing "written by a newer
 * version I cannot check" from "broken" needs the UNVERIFIABLE verdict, which is a later unit.
 *
 * <p>Per chained row, three independent checks: (a) when the payload is still present, it
 * must hash back to the persisted {@code payload_hash} (a redacted row — payload NULL —
 * skips this and verifies through the stored hash: erasure compatibility by construction);
 * (b) the stored fields plus the stored {@code prev_hash} and {@code payload_hash} must
 * recompute to the stored {@code entry_hash}; (c) {@code prev_hash} must equal the previous
 * entry's {@code entry_hash} (the tenant-derived genesis hash for the first). Sequence
 * numbers must be contiguous from 1, and unhashed pre-chain rows are legal only as a prefix.
 *
 * <p>Runs under the caller's {@code TenantContext}, so RLS scopes every read: verifying one
 * tenant cannot even see another tenant's rows. Known residual (documented, closed by the
 * signature unit): an attacker with owner privileges who re-chains the ENTIRE suffix and
 * rewrites the head is not detectable by recomputation alone.
 */
@Service
public class AuditChainVerifier {

    private final AuditLogEntryRepository logRepository;
    private final AuditLogEntryMapper logMapper;
    private final AuditChainHasher hasher;

    public AuditChainVerifier(AuditLogEntryRepository logRepository,
                              AuditLogEntryMapper logMapper,
                              AuditChainHasher hasher) {
        this.logRepository = logRepository;
        this.logMapper = logMapper;
        this.hasher = hasher;
    }

    /** Verifies {@code tenantId}'s full chain in sequence order. */
    @Transactional(readOnly = true)
    public ChainVerificationResult verifyChain(UUID tenantId) {
        List<AuditLogEntryEntity> entities =
                logRepository.findByTenantIdOrderBySequenceNumberAsc(tenantId);
        long chainedCount = 0;
        long preChainCount = 0;
        long expectedSequence = 1;
        String expectedPrev = hasher.genesisHash(tenantId);
        boolean chainStarted = false;
        for (AuditLogEntryEntity entity : entities) {
            AuditLogEntry entry = logMapper.toDomain(entity);
            long sequence = entry.sequenceNumber();
            if (sequence != expectedSequence) {
                return ChainVerificationResult.broken(sequence, ChainBreakKind.SEQUENCE_GAP,
                        chainedCount, preChainCount);
            }
            expectedSequence++;
            if (entry.entryHash() == null) {
                if (chainStarted) {
                    return ChainVerificationResult.broken(sequence,
                            ChainBreakKind.PRE_CHAIN_AFTER_CHAINED, chainedCount, preChainCount);
                }
                preChainCount++;
                continue;
            }
            chainStarted = true;
            // The scheme comes from the ROW, never from a constant: a v1 entry is verified
            // under v1 rules forever, and a chain may legitimately mix the two.
            String scheme = entry.hashScheme();
            if (entry.payloadHash() == null || entry.prevHash() == null
                    || !hasher.supports(scheme)) {
                return ChainVerificationResult.broken(sequence, ChainBreakKind.MALFORMED_ENTRY,
                        chainedCount, preChainCount);
            }
            if (entry.payload() != null
                    && !hasher.payloadHash(scheme, entry.payload()).equals(entry.payloadHash())) {
                return ChainVerificationResult.broken(sequence,
                        ChainBreakKind.PAYLOAD_HASH_MISMATCH, chainedCount, preChainCount);
            }
            if (!entry.prevHash().equals(expectedPrev)) {
                return ChainVerificationResult.broken(sequence,
                        ChainBreakKind.PREV_HASH_MISMATCH, chainedCount, preChainCount);
            }
            String recomputed = hasher.entryHash(scheme, entry.id(), entry.tenantId(), sequence,
                    entry.outboxId(), entry.eventType(), entry.drainedAt(), entry.payloadHash(),
                    entry.prevHash());
            if (!recomputed.equals(entry.entryHash())) {
                return ChainVerificationResult.broken(sequence,
                        ChainBreakKind.ENTRY_HASH_MISMATCH, chainedCount, preChainCount);
            }
            expectedPrev = entry.entryHash();
            chainedCount++;
        }
        return ChainVerificationResult.valid(chainedCount, preChainCount);
    }
}
