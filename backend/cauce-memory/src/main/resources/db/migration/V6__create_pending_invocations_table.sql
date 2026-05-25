-- V6: pending_invocations table -- the persistent queue for the asynchronous LLM
-- orchestrator. Each row is one queued LLM invocation triggered by a USER message that
-- needs an agent reply. A worker (later commit) claims PENDING rows with
-- SELECT ... FOR UPDATE SKIP LOCKED and drives them through the lifecycle. The entity is
-- associated directly with its owning (CLIENT) tenant, so visibility composes
-- tenant_is_visible (V1) directly, without traversing agent/conversation.
--
-- RLS NOTE: same as V1-V5 -- RLS is enabled here, but the development application user
-- is a PostgreSQL superuser and bypasses RLS; enforcement is exercised in integration
-- tests via a dedicated least-privilege role.
--   TODO: when application endpoints and authentication are introduced, connect the
--   application as a least-privilege role (cauce_app) without SUPERUSER so RLS enforces
--   tenant isolation at runtime.
--
-- conversation_id and trigger_message_id are intentionally NOT foreign keys: the queue is
-- deliberately loosely coupled to conversations/messages. If a conversation is deleted
-- while an invocation is still queued, the worker resolves the missing target at
-- processing time and marks the row FAILED, rather than the database forbidding the
-- delete. status is a closed domain enum and keeps its CHECK; the coherence CHECKs encode
-- the state/timestamp invariants the domain already guarantees (defence in depth against
-- any code path that bypasses the domain).

CREATE TABLE pending_invocations (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    conversation_id    UUID        NOT NULL,
    trigger_message_id UUID        NOT NULL,
    status             VARCHAR(20) NOT NULL
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'ABANDONED')),
    attempt_count      INTEGER     NOT NULL DEFAULT 0,
    max_attempts       INTEGER     NOT NULL DEFAULT 3,
    last_attempt_at    TIMESTAMPTZ,
    last_error         VARCHAR(1000),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at         TIMESTAMPTZ,
    claimed_by         VARCHAR(255),
    completed_at       TIMESTAMPTZ,
    -- A claimed (PROCESSING) row must record who claimed it and when.
    CONSTRAINT pending_invocations_processing_claimed CHECK (
        status <> 'PROCESSING' OR (claimed_at IS NOT NULL AND claimed_by IS NOT NULL)),
    -- A COMPLETED row must record when it finished.
    CONSTRAINT pending_invocations_completed_at_present CHECK (
        status <> 'COMPLETED' OR completed_at IS NOT NULL),
    -- A terminal failure must record both the finish instant and the error.
    CONSTRAINT pending_invocations_failed_shape CHECK (
        status <> 'FAILED' OR (completed_at IS NOT NULL AND last_error IS NOT NULL)),
    CONSTRAINT pending_invocations_abandoned_shape CHECK (
        status <> 'ABANDONED' OR (completed_at IS NOT NULL AND last_error IS NOT NULL))
);

-- Worker's hot path: oldest PENDING first. The composite (status, created_at) serves the
-- "claim next batch" scan (WHERE status = 'PENDING' ORDER BY created_at ASC).
CREATE INDEX idx_pending_invocations_status_created ON pending_invocations (status, created_at);
-- RLS / per-tenant queries.
CREATE INDEX idx_pending_invocations_tenant ON pending_invocations (tenant_id);
-- Debugging and partner queries by conversation.
CREATE INDEX idx_pending_invocations_conversation ON pending_invocations (conversation_id);

-- A pending invocation is visible exactly when its owning tenant is visible to the current
-- tenant context. Reuses tenant_is_visible from V1. SECURITY DEFINER so the tenant lookup
-- bypasses RLS (no recursion against pending_invocations).
CREATE FUNCTION pending_invocation_is_visible(pi_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = pi_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE pending_invocations ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON pending_invocations
    FOR ALL
    USING (pending_invocation_is_visible(tenant_id))
    WITH CHECK (pending_invocation_is_visible(tenant_id));
