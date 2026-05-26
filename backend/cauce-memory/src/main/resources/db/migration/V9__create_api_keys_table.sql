-- V9: api_keys table -- static API keys used to authenticate HTTP requests on behalf
-- of a tenant. Each row stores the bcrypt hash of the key (never the plaintext) plus
-- an 8-character non-secret prefix that the authentication filter uses as a fast
-- lookup index (bcrypt verification is too expensive to run over the whole table, but
-- it is cheap to fetch the rows that share a prefix and verify only those).
--
-- RLS NOTE: same as V1-V8 -- RLS is enabled here, but the development application user
-- is a PostgreSQL superuser and bypasses RLS; enforcement is exercised in integration
-- tests via a dedicated least-privilege role.
--   TODO: when application endpoints and authentication are introduced, connect the
--   application as a least-privilege role (cauce_app) without SUPERUSER so RLS enforces
--   tenant isolation at runtime.
--
-- Coherence CHECKs encode invariants the domain already guarantees (revocation and
-- expiry instants are not before creation), as defence in depth against any code path
-- that bypasses the domain.

CREATE TABLE api_keys (
    id            UUID PRIMARY KEY,
    tenant_id     UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name          VARCHAR(200) NOT NULL,
    key_hash      VARCHAR(255) NOT NULL,
    key_prefix    VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ,
    CONSTRAINT api_keys_revoked_after_created CHECK (
        revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT api_keys_expires_after_created CHECK (
        expires_at IS NULL OR expires_at >= created_at)
);

-- Defence in depth against an (astronomically unlikely) bcrypt hash collision.
CREATE UNIQUE INDEX uq_api_keys_key_hash ON api_keys (key_hash);
-- Per-tenant listing.
CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id);
-- Filter's hot path: lookup by prefix among active (not revoked) keys.
CREATE INDEX idx_api_keys_prefix_active
    ON api_keys (key_prefix) WHERE revoked_at IS NULL;

-- An API key is visible exactly when its owning tenant is visible to the current
-- tenant context. Reuses tenant_is_visible from V1. SECURITY DEFINER so the tenant
-- lookup bypasses RLS (no recursion against api_keys).
CREATE FUNCTION api_key_is_visible(ak_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = ak_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON api_keys
    FOR ALL
    USING (api_key_is_visible(tenant_id))
    WITH CHECK (api_key_is_visible(tenant_id));
