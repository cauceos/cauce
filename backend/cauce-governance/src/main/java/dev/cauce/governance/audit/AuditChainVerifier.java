package dev.cauce.governance.audit;

import dev.cauce.governance.audit.signing.AuditSignatureCheck;
import dev.cauce.governance.audit.signing.AuditSignatureVerifier;
import dev.cauce.governance.audit.signing.UnverifiableReason;
import dev.cauce.governance.persistence.AuditLogEntryEntity;
import dev.cauce.governance.persistence.AuditLogEntryMapper;
import dev.cauce.governance.persistence.AuditLogEntryRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p>Signatures are checked alongside recomputation but reported SEPARATELY, in
 * {@link SignatureReport}: the verdict is the verdict of the recomputation. An unsigned entry
 * (every v1 entry, and anything written without a configured key) is not a failure, and a
 * signature this instance cannot check — no public key for its {@code key_id}, or a scheme it
 * does not implement — is counted as unverifiable rather than reported as a break. Only a
 * signature that is present, checkable and wrong breaks the chain.
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
    private final AuditSignatureVerifier signatureVerifier;

    public AuditChainVerifier(AuditLogEntryRepository logRepository,
                              AuditLogEntryMapper logMapper,
                              AuditChainHasher hasher,
                              AuditSignatureVerifier signatureVerifier) {
        this.logRepository = logRepository;
        this.logMapper = logMapper;
        this.hasher = hasher;
        this.signatureVerifier = signatureVerifier;
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
        SignatureAccumulator signatures = new SignatureAccumulator();
        for (AuditLogEntryEntity entity : entities) {
            AuditLogEntry entry = logMapper.toDomain(entity);
            long sequence = entry.sequenceNumber();
            if (sequence != expectedSequence) {
                return ChainVerificationResult.broken(sequence, ChainBreakKind.SEQUENCE_GAP,
                        chainedCount, preChainCount, signatures.toReport());
            }
            expectedSequence++;
            if (entry.entryHash() == null) {
                if (chainStarted) {
                    return ChainVerificationResult.broken(sequence,
                            ChainBreakKind.PRE_CHAIN_AFTER_CHAINED, chainedCount, preChainCount,
                            signatures.toReport());
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
                        chainedCount, preChainCount, signatures.toReport());
            }
            if (entry.payload() != null
                    && !hasher.payloadHash(scheme, entry.payload()).equals(entry.payloadHash())) {
                return ChainVerificationResult.broken(sequence,
                        ChainBreakKind.PAYLOAD_HASH_MISMATCH, chainedCount, preChainCount,
                        signatures.toReport());
            }
            if (!entry.prevHash().equals(expectedPrev)) {
                return ChainVerificationResult.broken(sequence,
                        ChainBreakKind.PREV_HASH_MISMATCH, chainedCount, preChainCount,
                        signatures.toReport());
            }
            String recomputed = hasher.entryHash(scheme, entry.id(), entry.tenantId(), sequence,
                    entry.outboxId(), entry.eventType(), entry.drainedAt(), entry.payloadHash(),
                    entry.prevHash());
            if (!recomputed.equals(entry.entryHash())) {
                return ChainVerificationResult.broken(sequence,
                        ChainBreakKind.ENTRY_HASH_MISMATCH, chainedCount, preChainCount,
                        signatures.toReport());
            }
            // The signature is checked LAST, over the hash just confirmed. An entry with no
            // signature is counted, never failed; one this instance cannot check is counted
            // as unverifiable; only a signature that is present, checkable and WRONG breaks
            // the chain.
            AuditSignatureCheck check = signatureVerifier.check(entry.signature(), entry.keyId(),
                    entry.signatureScheme(), entry.entryHash());
            if (check.outcome() == AuditSignatureCheck.Outcome.INVALID) {
                return ChainVerificationResult.broken(sequence, ChainBreakKind.SIGNATURE_MISMATCH,
                        chainedCount, preChainCount, signatures.toReport());
            }
            signatures.record(check);
            expectedPrev = entry.entryHash();
            chainedCount++;
        }
        return ChainVerificationResult.valid(chainedCount, preChainCount, signatures.toReport());
    }

    /** Tallies signature outcomes while the chain is walked, in first-seen order. */
    private static final class SignatureAccumulator {

        private long verified;
        private long unsigned;
        private long unverifiable;
        private final LinkedHashSet<String> missingKeyIds = new LinkedHashSet<>();
        private final LinkedHashMap<String, Long> compromisedKeyIds = new LinkedHashMap<>();

        void record(AuditSignatureCheck check) {
            switch (check.outcome()) {
                case UNSIGNED -> unsigned++;
                case VERIFIED -> {
                    verified++;
                    if (check.compromisedKey()) {
                        compromisedKeyIds.merge(check.keyId(), 1L, Long::sum);
                    }
                }
                case UNVERIFIABLE -> {
                    unverifiable++;
                    if (check.reason() == UnverifiableReason.MISSING_PUBLIC_KEY) {
                        missingKeyIds.add(check.keyId());
                    }
                }
                case INVALID -> throw new IllegalStateException(
                        "an invalid signature is a break, not a tally");
            }
        }

        SignatureReport toReport() {
            return new SignatureReport(verified, unsigned, unverifiable,
                    List.copyOf(missingKeyIds), Map.copyOf(compromisedKeyIds));
        }
    }
}
