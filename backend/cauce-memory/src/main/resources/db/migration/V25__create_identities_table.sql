-- V25: identities table -- the interlocutor's identity on one channel (ADR 0004, decision 1).
-- One row per (tenant, channel_type, kind, value): the same person talking to two agents of
-- the same tenant is one identity; the same person on two channels is two, until the memory
-- subject exists. Conversations will reference this table instead of storing an opaque
-- string (the re-key of conversations is a later migration; this one only creates the
-- entity, so the existing conversation columns and their indexes are untouched here).
--
-- tenant_id is the owning tenant of the agent the interlocutor talks to, resolved by the
-- application at creation -- the same rationale as channel_configs (V16): the row carries
-- its tenant so hierarchical RLS keys on it directly, and a partner ingesting on behalf of
-- its client creates the client's identity (the policy's WITH CHECK admits it because the
-- client is visible to the partner).
--
-- kind is a closed domain enum (IdentityKind) and keeps a CHECK: adding a kind is a
-- migration, deliberately, because it is a domain change. channel_type is a free-form
-- identifier owned by the cauce-channels SPI and intentionally has no CHECK, so the
-- database does not hardcode the channel list (same convention as conversations and
-- channel_configs). value has no format CHECK either: the core stores and compares, it
-- does not interpret -- format and normalisation belong to the adapter that produced it.
--
-- The UNIQUE constraint is the ON CONFLICT arbiter of the insert-first resolve-or-create
-- in the application (mirrors the V13 partial index for OPEN conversations): two concurrent
-- first messages from the same interlocutor cannot mint two identities. It leads with
-- tenant_id, so it also serves the per-tenant RLS lookups; no separate tenant index.
--
-- No grant needed: cauce_app inherits DML on new tables via the V10 ALTER DEFAULT
-- PRIVILEGES.

CREATE TABLE identities (
    id           UUID PRIMARY KEY,
    tenant_id    UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    channel_type VARCHAR(20)  NOT NULL,
    kind         VARCHAR(20)  NOT NULL CHECK (kind IN (
                     'PHONE_NUMBER', 'EMAIL_ADDRESS', 'PROVIDER_USER_ID',
                     'CLIENT_REFERENCE', 'SESSION_ID')),
    value        VARCHAR(320) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT identities_key UNIQUE (tenant_id, channel_type, kind, value)
);

-- An identity is visible exactly when its owning tenant is visible to the current tenant
-- context. Mirrors channel_config_is_visible (V16): the row carries tenant_id, so
-- visibility composes tenant_is_visible (V1) directly. SECURITY DEFINER so the tenant
-- lookup bypasses RLS (no recursion against identities).
CREATE FUNCTION identity_is_visible(ident_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = ident_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE identities ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON identities
    FOR ALL
    USING (identity_is_visible(tenant_id))
    WITH CHECK (identity_is_visible(tenant_id));
