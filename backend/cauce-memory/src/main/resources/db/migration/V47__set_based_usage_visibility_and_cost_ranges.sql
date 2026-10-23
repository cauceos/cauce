-- V47: make the usage ledger and its cost view cheap to aggregate (ADR 0010, consequences).
--
-- Measured with 100 clients of one partner and 4,000 calls each (400,000 rows): the partner's month
-- took 10-30 s and one client's month 250-300 ms, and the client's month is read before every
-- invocation of a client with a spend limit. Two costs, both per row:
--
-- 1. The V19 policy called llm_usage_record_is_visible for every row: a plpgsql function that reads
--    the row's tenant and calls tenant_is_visible, about 10 us a row. The policy is now set based:
--    visible_tenant_ids() returns the current tenant's subtree once per query (itself, its children,
--    its grandchildren: the tenant_is_visible relation of V1), and the planner checks each row
--    against it as a hashed subplan. Same rows visible; no context still sees nothing.
--
-- 2. The V46 view looked the price up per row with a LATERAL subquery whose cache keyed on
--    created_at, so it missed on nearly every row and ran the price's RLS function each time. The
--    view now computes each price's validity range once (valid_from until the next price of the
--    same provider and model, ties broken by id as before) and joins the usage rows by range. Same
--    price chosen; a call before any price still has a null cost.
--
-- After both: one client's month in 9 ms, the partner's month by tenant in about 2 s.

CREATE FUNCTION visible_tenant_ids() RETURNS SETOF uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT id FROM tenants WHERE id = current_tenant_id()
    UNION ALL
    SELECT id FROM tenants WHERE parent_tenant_id = current_tenant_id()
    UNION ALL
    SELECT t.id FROM tenants t JOIN tenants p ON p.id = t.parent_tenant_id
    WHERE p.parent_tenant_id = current_tenant_id();
$$;

DROP POLICY hierarchical_visibility ON llm_usage_records;
DROP FUNCTION llm_usage_record_is_visible(uuid);

CREATE POLICY hierarchical_visibility ON llm_usage_records
    FOR ALL
    USING (tenant_id IN (SELECT visible_tenant_ids()))
    WITH CHECK (tenant_id IN (SELECT visible_tenant_ids()));

CREATE OR REPLACE VIEW llm_usage_costs WITH (security_invoker = true) AS
WITH prices AS (
    SELECT lp.id,
           lp.provider,
           lp.model,
           lp.input_usd_per_mtok,
           lp.output_usd_per_mtok,
           lp.cache_read_usd_per_mtok,
           lp.cache_write_usd_per_mtok,
           lp.valid_from,
           lead(lp.valid_from) OVER (PARTITION BY lp.provider, lp.model ORDER BY lp.valid_from, lp.id) AS valid_to
    FROM llm_prices lp
)
SELECT u.id,
       u.tenant_id,
       u.agent_id,
       u.conversation_id,
       u.invocation_id,
       u.provider,
       u.model,
       u.input_tokens,
       u.output_tokens,
       u.cache_read_tokens,
       u.cache_write_tokens,
       u.created_at,
       p.id AS price_id,
       ((u.input_tokens - u.cache_read_tokens - u.cache_write_tokens) * p.input_usd_per_mtok
            + u.cache_read_tokens * coalesce(p.cache_read_usd_per_mtok, p.input_usd_per_mtok)
            + u.cache_write_tokens * coalesce(p.cache_write_usd_per_mtok, p.input_usd_per_mtok)
            + u.output_tokens * p.output_usd_per_mtok) / 1000000 AS cost_usd
FROM llm_usage_records u
LEFT JOIN prices p
       ON p.provider = u.provider
      AND p.model = u.model
      AND p.valid_from <= u.created_at
      AND (p.valid_to IS NULL OR u.created_at < p.valid_to);
