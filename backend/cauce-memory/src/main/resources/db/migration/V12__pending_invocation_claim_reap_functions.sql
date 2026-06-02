-- V12: SECURITY DEFINER claim/reap for the async worker, runnable under cauce_app.
--
-- The worker and reaper drain pending_invocations across ALL tenants, so they run without
-- a tenant context. Under the least-privilege cauce_app role the underlying queries would
-- be filtered to nothing by the pending_invocations RLS policy (no app.current_tenant_id =>
-- fail-closed). These owner-owned SECURITY DEFINER functions are the NARROW cross-tenant
-- escape hatch: they bypass RLS as the owner, return the minimal rows (+ tenant_id), and the
-- caller then sets the tenant context and does ALL further work under RLS. See ADR 0001.
--
-- Asymmetry by design: claim performs the PENDING -> PROCESSING transition atomically in SQL
-- (queue mechanics that must be atomic under the row lock to prevent double-claim); reap only
-- DISCOVERS orphans, leaving the recovery transition (release/abandon, with backoff policy)
-- to the domain under RLS in each row's tenant context.

-- claim: atomically claim up to p_batch_size of the oldest eligible PENDING rows and move
-- them to PROCESSING, returning the claimed rows. FOR UPDATE SKIP LOCKED makes concurrent
-- workers claim disjoint sets. VOLATILE (it writes); not STABLE.
CREATE FUNCTION claim_pending_invocations(p_worker_id text, p_batch_size integer)
    RETURNS SETOF pending_invocations
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    UPDATE pending_invocations
    SET status        = 'PROCESSING',
        claimed_at    = now(),
        claimed_by    = p_worker_id,
        attempt_count = attempt_count + 1,
        last_attempt_at = now()
    WHERE id IN (
        SELECT id FROM pending_invocations
        WHERE status = 'PENDING'
          AND (next_attempt_at IS NULL OR next_attempt_at <= now())
        ORDER BY created_at ASC
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    RETURNING *;
$$;

-- reap (discovery only): return PROCESSING rows whose claim is older than p_threshold, so the
-- reaper can recover work whose worker died or hung. The recovery transition stays in the
-- domain under RLS. STABLE (read-only).
CREATE FUNCTION orphaned_invocations(p_threshold timestamptz)
    RETURNS SETOF pending_invocations
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT * FROM pending_invocations
    WHERE status = 'PROCESSING' AND claimed_at < p_threshold;
$$;

-- Escape-hatch functions are executable only by the runtime role, never PUBLIC (ADR 0001).
-- The owner retains execute implicitly. This also tightens V11's api_keys lookup, which was
-- created relying on the default PUBLIC execute privilege.
REVOKE EXECUTE ON FUNCTION claim_pending_invocations(text, integer) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION claim_pending_invocations(text, integer) TO cauce_app;

REVOKE EXECUTE ON FUNCTION orphaned_invocations(timestamptz) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION orphaned_invocations(timestamptz) TO cauce_app;

REVOKE EXECUTE ON FUNCTION api_keys_active_by_prefix(text) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION api_keys_active_by_prefix(text) TO cauce_app;
