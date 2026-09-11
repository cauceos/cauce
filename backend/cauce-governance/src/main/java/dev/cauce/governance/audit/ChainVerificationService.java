package dev.cauce.governance.audit;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Verifies a tenant's chain AND records the verification in that chain (ADR 0003 §5). The
 * public verification endpoint's only collaborator, and — deliberately — the only caller of
 * {@link ChainVerificationRecorder}.
 *
 * <p><b>On demand only. Never attach this to a scheduler, a startup hook, or a write path.</b>
 * The ledger recording its own verifications is valuable precisely because each entry answers
 * a real question someone asked; a timer would make the chain grow without adding
 * information. {@link AuditChainVerifier#verifyChain} stays pure for every other use —
 * internal checks, tests, a future export — none of which should leave a trace.
 *
 * <p>Atomicity, in two halves that cannot interfere:
 * <ol>
 *   <li>Verification runs first, read-only, in its own transaction. If it fails mid-way,
 *       the recording step never starts: nothing was written.</li>
 *   <li>Recording runs second, in its own short transaction, into the OUTBOX — the same path
 *       as every other audit event. The chain itself is appended by the drainer, under the
 *       head lock, atomically with the rest of the batch. This service never touches the
 *       ledger or the head, so it cannot leave them inconsistent.</li>
 * </ol>
 * If recording fails, the whole call fails: a verification this design could not record is
 * not a verification of this design, and returning its verdict anyway would silently break
 * the property that the ledger contains its own verification history. Same rule as capture
 * everywhere else — commit with the fact, or not at all.
 *
 * <p>The entry appears in the chain one drainer tick later, not in the response that
 * triggered it. The NEXT verification checks it, counts it, and records itself in turn.
 */
@Service
public class ChainVerificationService {

    private final AuditChainVerifier verifier;
    private final ChainVerificationRecorder recorder;

    public ChainVerificationService(AuditChainVerifier verifier,
                                    ChainVerificationRecorder recorder) {
        this.verifier = verifier;
        this.recorder = recorder;
    }

    /**
     * Verifies {@code tenantId}'s chain on behalf of {@code actorTenantId}, then records the
     * outcome in {@code tenantId}'s chain.
     */
    public ChainVerificationResult verifyAndRecord(UUID tenantId, UUID actorTenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        ChainVerificationResult result = verifier.verifyChain(tenantId);
        recorder.record(tenantId, actorTenantId, result);
        return result;
    }
}
