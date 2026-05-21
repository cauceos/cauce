-- V2: agents table. Each agent belongs to a CLIENT tenant.
--
-- RLS NOTE: same as V1 — RLS is enabled here, but the development application user
-- is a PostgreSQL superuser and bypasses RLS; enforcement is exercised in
-- integration tests via a dedicated least-privilege role.
--   TODO: when application endpoints and authentication are introduced, connect the
--   application as a least-privilege role (cauce_app) without SUPERUSER so RLS
--   enforces tenant isolation at runtime.
--
-- model_provider is a free-form identifier validated by the application service
-- against a temporary known set (to be replaced by the cauce-llm provider registry
-- when the SPI exists); it intentionally has no CHECK constraint so the database
-- does not hardcode the provider list. status is a closed domain enum and keeps its CHECK.

CREATE TABLE agents (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name           VARCHAR(200) NOT NULL,
    system_prompt  TEXT NOT NULL,
    model_provider VARCHAR(20)  NOT NULL,
    model_name     VARCHAR(100) NOT NULL,
    status         VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_agents_tenant ON agents (tenant_id);
CREATE INDEX idx_agents_status ON agents (status);

-- An agent is visible exactly when its owning tenant is visible to the current
-- tenant context. Reuses tenant_is_visible from V1. SECURITY DEFINER so the tenant
-- lookups bypass RLS (no recursion against agents).
CREATE FUNCTION agent_is_visible(agent_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = agent_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE agents ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON agents
    FOR ALL
    USING (agent_is_visible(tenant_id))
    WITH CHECK (agent_is_visible(tenant_id));
