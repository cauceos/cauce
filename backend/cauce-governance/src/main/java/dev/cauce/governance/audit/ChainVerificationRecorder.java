package dev.cauce.governance.audit;

import dev.cauce.core.audit.AuditEventRecorder;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transactional step of recording a verification: an outbox INSERT through the same
 * {@link AuditEventRecorder} port every other audit emitter uses. A separate bean from
 * {@link ChainVerificationService} on purpose — the port's {@code MANDATORY} propagation
 * needs a real transaction, and a {@code @Transactional} method invoked from inside its own
 * class would bypass the proxy (and, as a {@code @Service} method, this one also lets
 * {@code RlsContextAspect} set the tenant GUC before the INSERT).
 *
 * <p>Writes to the OUTBOX, never to the ledger. The entry joins the chain when the drainer
 * drains it, under the head lock, in the same atomic batch as any other entry. This class
 * therefore cannot leave the ledger or the head inconsistent: it does not touch them.
 */
@Service
public class ChainVerificationRecorder {

    private final AuditEventRecorder recorder;

    public ChainVerificationRecorder(AuditEventRecorder recorder) {
        this.recorder = recorder;
    }

    /** Records that {@code actorTenantId} verified {@code tenantId}'s chain and found {@code result}. */
    @Transactional
    public void record(UUID tenantId, UUID actorTenantId, ChainVerificationResult result) {
        recorder.record(LedgerAuditEvents.chainVerified(tenantId, actorTenantId, result));
    }
}
