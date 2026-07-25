-- V19: llm_usage_records -- the per-tenant LLM token usage ledger. One row per LLM call
-- (a multi-round agentic invocation writes one row per round), written synchronously by the
-- orchestrator in its own short transaction immediately after the provider responds and
-- before the LlmResponded event is published. Rows are immutable facts (tokens, provider,
-- model); cost is deliberately NOT materialized -- provider prices change, and a stored cost
-- would turn a tariff update into retroactive corruption of the ledger. Pricing will be a
-- separate versioned table and cost a view over these facts.
--
-- agent_id / conversation_id / invocation_id are intentionally NOT foreign keys: usage rows
-- are billing facts that must outlive future retention purges of operational rows (an FK
-- would either block the purge or cascade away the facts). Only tenant_id -- the RLS anchor,
-- and tenants are never deleted -- is enforced. finish_reason has no value CHECK: the
-- vocabulary is owned by the cauce-llm FinishReason enum and may grow. There is no unique
-- constraint on (invocation_id, round_index): a worker retry re-runs already-executed rounds,
-- and each row is one real provider-billed call.

CREATE TABLE llm_usage_records (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    agent_id        UUID         NOT NULL,
    conversation_id UUID         NOT NULL,
    invocation_id   UUID         NOT NULL,
    provider        VARCHAR(20)  NOT NULL,
    model           VARCHAR(100) NOT NULL,
    round_index     INTEGER      NOT NULL CHECK (round_index >= 0),
    input_tokens    INTEGER      NOT NULL CHECK (input_tokens >= 0),
    output_tokens   INTEGER      NOT NULL CHECK (output_tokens >= 0),
    total_tokens    INTEGER      NOT NULL CHECK (total_tokens >= 0),
    finish_reason   VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The real query this table exists for: usage per tenant over a period. Filters by agent or
-- model ride the same scan (both are columns on the row); dedicated indexes are a pure
-- additive follow-up if the dashboard's aggregations ever need them.
CREATE INDEX idx_llm_usage_records_tenant_created ON llm_usage_records (tenant_id, created_at);

-- A usage row is visible exactly when its owning tenant is visible to the current tenant
-- context. Reuses tenant_is_visible from V1; same shape as V6/V16. SECURITY DEFINER so the
-- tenant lookup bypasses RLS.
CREATE FUNCTION llm_usage_record_is_visible(ur_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = ur_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE llm_usage_records ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON llm_usage_records
    FOR ALL
    USING (llm_usage_record_is_visible(tenant_id))
    WITH CHECK (llm_usage_record_is_visible(tenant_id));
