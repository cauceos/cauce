package dev.cauce.governance.audit;

/**
 * The write port of the audit trail: a business call site records an auditable action as
 * one line INSIDE the transaction it is already running. The implementation joins that
 * transaction (never opens its own), so the audit capture commits or rolls back atomically
 * with the business fact it describes — the transactional-outbox guarantee.
 *
 * <p>No production callers exist yet: wiring the real auditable actions (orchestrator loop,
 * tenancy operations) is a follow-up unit. Future callers add a dependency on
 * cauce-governance (acyclic: governance depends only on cauce-core and cauce-memory).
 */
public interface AuditEventRecorder {

    /**
     * Captures {@code event} in the caller's active transaction.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if no
     *     transaction is active — recording an audit fact outside the business transaction
     *     it audits is a programming error
     */
    void record(AuditEvent event);
}
