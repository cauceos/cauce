package dev.cauce.core.audit;

/**
 * The write port of the audit trail: a business call site records an auditable action as
 * one line INSIDE the transaction it is already running. The implementation joins that
 * transaction (never opens its own), so the audit capture commits or rolls back atomically
 * with the business fact it describes — the transactional-outbox guarantee.
 *
 * <p>Port in cauce-core, adapter in cauce-governance ({@code OutboxAuditEventRecorder}) —
 * the same shape as {@code AgentReplyDispatcher}/{@code ApiKeyHasher}: emitting modules
 * (orchestration now, tenancy later) depend only on core, and governance stays an adapter
 * rather than a hub. Injection is REQUIRED by design: audit capture is a guarantee, not an
 * option — a deployment missing the adapter must fail at startup, not run with a silent
 * hole in its trail.
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
