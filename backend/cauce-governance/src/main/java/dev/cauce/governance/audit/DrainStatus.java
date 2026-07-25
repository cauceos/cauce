package dev.cauce.governance.audit;

/**
 * Lifecycle of an {@link AuditOutboxEntry}: captured in the business transaction
 * ({@code PENDING}), then moved to the append-only ledger by the drainer ({@code DRAINED}).
 */
public enum DrainStatus {
    PENDING,
    DRAINED
}
