-- V8: backoff support for pending_invocations. The async worker (cauce-orchestration)
-- reschedules a transient failure by releasing the row back to PENDING with a
-- next_attempt_at in the future, computed as now + baseInterval * 2^(attemptCount-1).
-- Rows whose next_attempt_at is NULL OR <= NOW() are eligible for the next claim.
--
-- Rows that existed before this migration get next_attempt_at = NULL and are therefore
-- immediately eligible, preserving prior semantics.
--
-- A partial index on PENDING rows makes the worker's hot-path scan
--   WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
--   ORDER BY created_at ASC
-- both small and cheap: COMPLETED/FAILED/ABANDONED rows are not indexed, and
-- NULLS FIRST orders rows without a scheduled retry alongside the oldest backoff-cleared
-- rows so the worker picks them deterministically by created_at.
--
-- The existing (status, created_at) index is kept; it serves administrative queries
-- that scan terminal states and is not made redundant by the partial index.

ALTER TABLE pending_invocations
    ADD COLUMN next_attempt_at TIMESTAMPTZ NULL;

CREATE INDEX idx_pending_invocations_pending_ready
    ON pending_invocations (next_attempt_at NULLS FIRST, created_at)
    WHERE status = 'PENDING';
