-- V16: channel_configs table -- the binding of one channel instance (e.g. a Telegram bot)
-- to an agent. Inbound webhook traffic addressed to a config is ingested for its agent,
-- under that agent's tenant.
--
-- tenant_id is the owning (CLIENT) tenant of the bound agent, resolved at creation --
-- exactly the pending_invocations rationale (V6): the unauthenticated webhook path must
-- discover the owning tenant from the row itself, and hierarchical RLS keys on it
-- directly. agent_id keeps referential integrity to the bound agent.
--
-- channel_type is a free-form identifier validated by the application service against the
-- cauce-channels adapter registry (the SPI now exists); it intentionally has no CHECK so
-- the database does not hardcode the channel list. status is a closed domain enum and
-- keeps its CHECK.
--
-- credential is the provider credential (Telegram bot token), needed by the outbound
-- half; stored as-is for now -- encryption-at-rest is deferred alongside per-tenant LLM
-- credentials (TODO). webhook_secret_hash stores only the hash of the server-generated
-- webhook secret (the plaintext is returned once at creation, mirroring api_keys).
--
-- No grant needed: cauce_app inherits DML on new tables via the V10 ALTER DEFAULT
-- PRIVILEGES.

CREATE TABLE channel_configs (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    agent_id            UUID         NOT NULL REFERENCES agents (id) ON DELETE RESTRICT,
    channel_type        VARCHAR(20)  NOT NULL,
    credential          VARCHAR(512) NOT NULL,
    webhook_secret_hash VARCHAR(255) NOT NULL,
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- RLS / per-tenant queries, and "channels of this agent" (the operational lookup).
CREATE INDEX idx_channel_configs_tenant ON channel_configs (tenant_id);
CREATE INDEX idx_channel_configs_agent ON channel_configs (agent_id);

-- A channel config is visible exactly when its owning tenant is visible to the current
-- tenant context. Mirrors pending_invocation_is_visible (V6): the row carries tenant_id,
-- so visibility composes tenant_is_visible (V1) directly. SECURITY DEFINER so the tenant
-- lookup bypasses RLS (no recursion against channel_configs).
CREATE FUNCTION channel_config_is_visible(cc_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = cc_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE channel_configs ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON channel_configs
    FOR ALL
    USING (channel_config_is_visible(tenant_id))
    WITH CHECK (channel_config_is_visible(tenant_id));

-- SECURITY DEFINER resolution for the webhook path (ADR 0001, pattern 2; mirrors the
-- API-key prefix lookup V11). A provider webhook arrives with no tenant context -- this
-- lookup is what DISCOVERS the tenant -- so under cauce_app the RLS policy would
-- fail-close it. Minimal operation, minimal rows: exactly one ACTIVE config by primary
-- key, tenant_id included on the row. Exposing the row to the caller is safe: the webhook
-- flow still verifies the channel secret against webhook_secret_hash before trusting the
-- request, and a config id alone authenticates nobody.
CREATE FUNCTION resolve_active_channel_config(p_config_id uuid)
    RETURNS SETOF channel_configs
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT * FROM channel_configs
    WHERE id = p_config_id AND status = 'ACTIVE';
$$;
