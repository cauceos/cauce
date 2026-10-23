-- V53: the state the operator is alerted on, read across tenants as aggregates (ADR 0011, M2).
--
-- The queue, the audit outbox, the owner notices and the spend alerts live in tenant-scoped tables;
-- a gauge that reports them for the whole instance runs without a tenant context, where RLS
-- fail-closes to nothing under cauce_app. These two owner-owned SECURITY DEFINER functions are the
-- narrow escape hatch (ADR 0001): they return counts, ages and sums, never a row. Ages are measured
-- with the database clock (ADR 0008).
--
-- operational_snapshot(): one row.
--   pending_invocations          PENDING rows in the queue
--   oldest_due_pending_seconds   how long the oldest PENDING row that is due (its backoff over) has
--                                waited; 0 when none is due. A stuck worker shows here first.
--   audit_outbox_pending         outbox rows not yet drained into the chain
--   oldest_audit_outbox_seconds  how long the oldest of them has waited; 0 when none
--   owner_notices_unsent_24h     owner notices NOT_SENT or FAILED in the last 24 hours
--   spend_alerts_80_month        clients past 80 % of their limit this month (UTC)
--   spend_alerts_100_month       clients that reached their limit this month (UTC)
--
-- month_spend_by_tenant(): one row per tenant with priced usage this month (UTC): its spend in US$.
-- The tenant id is a metric tag (M1); nothing else about the tenant leaves.

CREATE FUNCTION operational_snapshot()
    RETURNS TABLE (
        pending_invocations         bigint,
        oldest_due_pending_seconds  double precision,
        audit_outbox_pending        bigint,
        oldest_audit_outbox_seconds double precision,
        owner_notices_unsent_24h    bigint,
        spend_alerts_80_month       bigint,
        spend_alerts_100_month      bigint)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT
        (SELECT count(*) FROM pending_invocations WHERE status = 'PENDING'),
        (SELECT coalesce(extract(epoch FROM now() - min(coalesce(next_attempt_at, created_at))), 0)::double precision
           FROM pending_invocations
          WHERE status = 'PENDING' AND coalesce(next_attempt_at, created_at) <= now()),
        (SELECT count(*) FROM audit_outbox WHERE drain_status = 'PENDING'),
        (SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)::double precision
           FROM audit_outbox WHERE drain_status = 'PENDING'),
        (SELECT count(*) FROM owner_notifications
          WHERE status IN ('NOT_SENT', 'FAILED') AND updated_at > now() - interval '24 hours'),
        (SELECT count(*) FROM spend_alerts
          WHERE threshold_percent = 80 AND period = date_trunc('month', now() AT TIME ZONE 'UTC')::date),
        (SELECT count(*) FROM spend_alerts
          WHERE threshold_percent = 100 AND period = date_trunc('month', now() AT TIME ZONE 'UTC')::date);
$$;

CREATE FUNCTION month_spend_by_tenant()
    RETURNS TABLE (tenant_id uuid, spent_usd numeric)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT c.tenant_id, sum(c.cost_usd)
      FROM llm_usage_costs c
     WHERE c.created_at >= date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
       AND c.cost_usd IS NOT NULL
     GROUP BY c.tenant_id;
$$;

-- Escape-hatch functions are executable only by the runtime role, never PUBLIC (ADR 0001).
REVOKE EXECUTE ON FUNCTION operational_snapshot() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION operational_snapshot() TO cauce_app;
REVOKE EXECUTE ON FUNCTION month_spend_by_tenant() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION month_spend_by_tenant() TO cauce_app;
