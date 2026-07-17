-- Persist the failure taxonomy on the invocation row.
--
-- Until now the InvocationFailureType only travelled on the InvocationFailed event; the row
-- kept just last_error (a free-text string that may carry raw provider detail and is never
-- exposed to clients). The invocation-status endpoint needs a client-safe failure cause, and
-- two different causes collapse into ABANDONED (retries exhausted vs reaper timeout), so the
-- type must be recorded at transition time — it cannot be re-derived at read time.
--
-- The column is nullable: rows that failed before this migration have no recorded type
-- (surfaced as a null failure reason), and non-terminal or COMPLETED rows never get one.
-- No value-list CHECK: the taxonomy is owned by InvocationFailureType in
-- cauce-orchestration-events and only trusted code (worker/reaper) writes it; growing the
-- enum must not require a migration. The shape constraint below encodes the real invariant.

ALTER TABLE pending_invocations
    ADD COLUMN failure_type VARCHAR(50);

ALTER TABLE pending_invocations
    ADD CONSTRAINT pending_invocations_failure_type_shape CHECK (
        failure_type IS NULL OR status IN ('FAILED', 'ABANDONED'));
