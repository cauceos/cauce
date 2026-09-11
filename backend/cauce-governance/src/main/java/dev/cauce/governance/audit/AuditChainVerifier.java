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
 * Recomputes one tenant's audit chain from the stored rows and issues one of the four
 * {@link ChainVerdict}s. Pure: it reads and reports, and never writes. Recording a
 * verification in the chain is {@link ChainVerificationService}'s job, and only on demand.
 *
 * <p>Per chained row, three independent checks: (a) when the payload is still present, it
 * must hash back to the persisted {@code payload_hash} (a redacted row — payload NULL —
 * skips this and verifies through the stored hash: erasure compatibility by construction);
 * (b) the stored fields plus the stored {@code prev_hash} and {@code payload_hash} must
 * recompute to the stored {@code entry_hash}; (c) {@code prev_hash} must equal the previous
 * entry's {@code entry_hash} (the tenant-derived genesis hash for the first). Sequence
 * numbers must be contiguous from 1, and unhashed pre-chain rows are legal only as a prefix.
 *
 * <p>Every check runs under the scheme the row itself records ({@code hash_scheme}), so
 * entries written under v1 keep verifying under v1 rules and a chain that mixes schemes —
 * legal, and expected while instances of different versions write it — verifies end to end.
 * A hash scheme this build does not implement STOPS the walk: everything from that entry on
 * is reported as unverifiable, never as broken, because "written by a newer version than me"
 * is not a defect and calling it one would be the easiest available lie.
 *
 * <p>Signatures are checked over the hash just confirmed and tallied in a
 * {@link SignatureReport}. An unsigned entry (every v1 entry, and anything written without a
 * configured key) is counted, never failed; one this instance cannot check — no public key
 * for its {@code key_id}, or a signature scheme it does not implement — makes the verdict
 * {@link ChainVerdict#UNVERIFIABLE}; only a signature that is present, checkable and wrong
 * breaks the chain.
 *
 * <p>The optional <b>anchor</b> is the one comparison a database cannot make against itself.
 * A caller that kept the head from an earlier verification passes it back; the verifier then
 * reports {@link ChainVerdict#TRUNCATED} when the consistent chain no longer reaches that
 * sequence, and {@link ChainBreakKind#ANCHOR_MISMATCH} when it reaches it with a different
 * hash. Without an anchor, truncation is never guessed.
 *
 * <p>Runs under the caller's {@code TenantContext}, so RLS scopes every read: verifying one
 * tenant cannot even see another tenant's rows. Known residual, stated on every response: an
 * actor holding the signing key — deployment access, not merely database access — is outside
 * what this detects.
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

    /** Verifies {@code tenantId}'s full chain in sequence order, with no anchor. */
    @Transactional(readOnly = true)
    public ChainVerificationResult verifyChain(UUID tenantId) {
        return verifyChain(tenantId, null);
    }

    /**
     * Verifies {@code tenantId}'s full chain in sequence order, against {@code anchor} when one
     * is given (the head the caller observed on an earlier verification).
     */
    @Transactional(readOnly = true)
    public ChainVerificationResult verifyChain(UUID tenantId, ChainHead anchor) {
        List<AuditLogEntryEntity> entities =
                logRepository.findByTenantIdOrderBySequenceNumberAsc(tenantId);
        Walk walk = new Walk(tenantId, anchor);
        for (AuditLogEntryEntity entity : entities) {
            if (!walk.step(logMapper.toDomain(entity))) {
                break;
            }
        }
        return walk.result();
    }

    /** The state of one walk down a chain, and the verdict it arrives at. */
    private final class Walk {

        private final ChainHead anchor;
        private final SignatureAccumulator signatures = new SignatureAccumulator();

        private long chainedCount;
        private long preChainCount;
        private long expectedSequence = 1;
        private String expectedPrev;
        private boolean chainStarted;
        private ChainHead head;
        private String hashAtAnchor;

        private Long brokenAt;
        private ChainBreakKind breakKind;
        private Long unverifiableFrom;

        Walk(UUID tenantId, ChainHead anchor) {
            this.anchor = anchor;
            this.expectedPrev = hasher.genesisHash(tenantId);
        }

        /** Checks one entry; returns false when the walk must stop (break or unverifiable). */
        boolean step(AuditLogEntry entry) {
            long sequence = entry.sequenceNumber();
            if (sequence != expectedSequence) {
                return fail(sequence, ChainBreakKind.SEQUENCE_GAP);
            }
            expectedSequence++;
            if (entry.entryHash() == null) {
                if (chainStarted) {
                    return fail(sequence, ChainBreakKind.PRE_CHAIN_AFTER_CHAINED);
                }
                preChainCount++;
                return true;
            }
            chainStarted = true;
            // The scheme comes from the ROW, never from a constant: a v1 entry is verified
            // under v1 rules forever, and a chain may legitimately mix the two.
            String scheme = entry.hashScheme();
            if (!hasher.supports(scheme)) {
                unverifiableFrom = sequence;
                return false;
            }
            if (entry.payloadHash() == null || entry.prevHash() == null) {
                return fail(sequence, ChainBreakKind.MALFORMED_ENTRY);
            }
            if (entry.payload() != null
                    && !hasher.payloadHash(scheme, entry.payload()).equals(entry.payloadHash())) {
                return fail(sequence, ChainBreakKind.PAYLOAD_HASH_MISMATCH);
            }
            if (!entry.prevHash().equals(expectedPrev)) {
                return fail(sequence, ChainBreakKind.PREV_HASH_MISMATCH);
            }
            String recomputed = hasher.entryHash(scheme, entry.id(), entry.tenantId(), sequence,
                    entry.outboxId(), entry.eventType(), entry.drainedAt(), entry.payloadHash(),
                    entry.prevHash());
            if (!recomputed.equals(entry.entryHash())) {
                return fail(sequence, ChainBreakKind.ENTRY_HASH_MISMATCH);
            }
            // The signature is checked LAST, over the hash just confirmed.
            AuditSignatureCheck check = signatureVerifier.check(entry.signature(), entry.keyId(),
                    entry.signatureScheme(), entry.entryHash());
            if (check.outcome() == AuditSignatureCheck.Outcome.INVALID) {
                return fail(sequence, ChainBreakKind.SIGNATURE_MISMATCH);
            }
            signatures.record(check);
            if (anchor != null && sequence == anchor.sequenceNumber()) {
                hashAtAnchor = entry.entryHash();
            }
            expectedPrev = entry.entryHash();
            head = new ChainHead(sequence, entry.entryHash());
            chainedCount++;
            return true;
        }

        private boolean fail(long sequence, ChainBreakKind kind) {
            brokenAt = sequence;
            breakKind = kind;
            return false;
        }

        ChainVerificationResult result() {
            ChainVerdict verdict = verdict();
            return new ChainVerificationResult(verdict, chainedCount, preChainCount,
                    verdict == ChainVerdict.BROKEN ? brokenAt : null,
                    verdict == ChainVerdict.BROKEN ? breakKind : null,
                    head, anchor, unverifiableFrom, signatures.toReport());
        }

        /** Precedence: BROKEN over TRUNCATED over UNVERIFIABLE over VALID — see ChainVerdict. */
        private ChainVerdict verdict() {
            if (brokenAt != null) {
                return ChainVerdict.BROKEN;
            }
            if (anchor != null) {
                if (unverifiableFrom != null && anchor.sequenceNumber() >= unverifiableFrom) {
                    // The anchor lies in the part this build could not check. No answer.
                    return ChainVerdict.UNVERIFIABLE;
                }
                if (hashAtAnchor == null) {
                    // Either the consistent chain never reaches that sequence — the signature
                    // of a restored backup — or a pre-chain row sits there, in which case the
                    // anchor cannot be from this chain's hashed part.
                    boolean reached =
                            head != null && head.sequenceNumber() >= anchor.sequenceNumber();
                    if (reached) {
                        fail(anchor.sequenceNumber(), ChainBreakKind.ANCHOR_MISMATCH);
                        return ChainVerdict.BROKEN;
                    }
                    return ChainVerdict.TRUNCATED;
                }
                if (!hashAtAnchor.equals(anchor.entryHash())) {
                    fail(anchor.sequenceNumber(), ChainBreakKind.ANCHOR_MISMATCH);
                    return ChainVerdict.BROKEN;
                }
            }
            if (unverifiableFrom != null || signatures.hasUnverifiable()) {
                return ChainVerdict.UNVERIFIABLE;
            }
            return ChainVerdict.VALID;
        }
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

        boolean hasUnverifiable() {
            return unverifiable > 0;
        }

        SignatureReport toReport() {
            return new SignatureReport(verified, unsigned, unverifiable,
                    List.copyOf(missingKeyIds), Map.copyOf(compromisedKeyIds));
        }
    }
}
