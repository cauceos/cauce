-- V46: the versioned price table and the cost view (ADR 0010, S1, S2).
--
-- llm_prices: the provider's list price of a model, in US$ per million tokens. A row is never
-- changed: a new price is a new row, in force from valid_from, which the application stamps with the
-- database clock when the row is added (ADR 0008), so a tariff change never reprices past calls.
-- The cache prices are optional: absent, cached tokens are priced as input. No migration ever
-- inserts a price: prices are data of the deployment, added by its operator over the API. provider
-- has no value CHECK, like llm_usage_records.provider: the vocabulary is the LLM SPI's.
--
-- Rows belong to the operator tenant. Reading follows the hierarchy in both directions: a tenant
-- reads the prices of itself, its descendants and its ancestors, so the cost of a client's usage is
-- computed under the client's own RLS. Writing is insert-only and needs the row's tenant visible;
-- there is no UPDATE or DELETE policy, so under RLS a price row cannot be changed or removed.
--
-- llm_usage_costs: every usage row with its cost, priced with the row of its provider and model in
-- force at its created_at; a null cost when none was. security_invoker, so the RLS of both tables
-- applies to whoever reads the view. Cost is derived here and never stored (CLAUDE.md, design rules).
-- input_tokens is the whole prompt (V43): its uncached part is what remains after the cache reads
-- and writes.
--
-- No grant needed: cauce_app inherits DML on the table and SELECT on the view via the V10 default
-- privileges.

CREATE TABLE llm_prices (
    id                       UUID PRIMARY KEY,
    tenant_id                UUID          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    provider                 VARCHAR(20)   NOT NULL,
    model                    VARCHAR(100)  NOT NULL,
    input_usd_per_mtok       NUMERIC(12,6) NOT NULL CHECK (input_usd_per_mtok >= 0),
    output_usd_per_mtok      NUMERIC(12,6) NOT NULL CHECK (output_usd_per_mtok >= 0),
    cache_read_usd_per_mtok  NUMERIC(12,6) CHECK (cache_read_usd_per_mtok >= 0),
    cache_write_usd_per_mtok NUMERIC(12,6) CHECK (cache_write_usd_per_mtok >= 0),
    valid_from               TIMESTAMPTZ   NOT NULL
);

-- The view's lookup: the latest price of a provider and model at a given instant.
CREATE INDEX idx_llm_prices_lookup ON llm_prices (provider, model, valid_from);
CREATE INDEX idx_llm_prices_tenant ON llm_prices (tenant_id, id);

-- Readable by the price's tenant, its ancestors and its descendants. SECURITY DEFINER so the
-- tenant lookups bypass RLS and do not recurse.
CREATE FUNCTION llm_price_is_readable(p_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    c uuid := current_tenant_id();
    t_parent uuid;
BEGIN
    IF c IS NULL THEN
        RETURN false;
    END IF;
    SELECT parent_tenant_id INTO t_parent FROM tenants WHERE id = p_tenant_id;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    IF tenant_is_visible(p_tenant_id, t_parent) THEN
        RETURN true; -- the price is the current tenant's own or a descendant's
    END IF;
    -- The price's tenant is the current tenant's parent or grandparent.
    RETURN EXISTS (
        SELECT 1 FROM tenants t
        WHERE t.id = c
          AND (t.parent_tenant_id = p_tenant_id
               OR EXISTS (SELECT 1 FROM tenants p WHERE p.id = t.parent_tenant_id AND p.parent_tenant_id = p_tenant_id))
    );
END;
$$;

-- Writable when the row's tenant is visible, the same relation as every other table.
CREATE FUNCTION llm_price_is_visible(p_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = p_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE llm_prices ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON llm_prices
    FOR SELECT
    USING (llm_price_is_readable(tenant_id));

CREATE POLICY insert_only ON llm_prices
    FOR INSERT
    WITH CHECK (llm_price_is_visible(tenant_id));

CREATE VIEW llm_usage_costs WITH (security_invoker = true) AS
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
LEFT JOIN LATERAL (
    SELECT lp.id, lp.input_usd_per_mtok, lp.output_usd_per_mtok, lp.cache_read_usd_per_mtok,
           lp.cache_write_usd_per_mtok
    FROM llm_prices lp
    WHERE lp.provider = u.provider
      AND lp.model = u.model
      AND lp.valid_from <= u.created_at
    ORDER BY lp.valid_from DESC, lp.id DESC
    LIMIT 1
) p ON true;
