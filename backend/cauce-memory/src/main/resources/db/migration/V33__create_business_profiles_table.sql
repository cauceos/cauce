-- V33: the business profile (ADR 0007, B1, B5).
--
-- One per business tenant: its name, its IANA time zone (bookings and the daily digest are in
-- local time), the language it speaks to its customers in, and the facts an agent may state
-- about it as an ordered JSON array of {key, value} -- ordered because the owner's order is how
-- the sheet reads. Sector-neutral: what the facts say is data, never a column.
-- Hierarchical RLS keyed on tenant_id, like whatsapp_apps (V30).
-- No grant needed: cauce_app inherits DML on new tables via the V10 default privileges.

CREATE TABLE business_profiles (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name        VARCHAR(200) NOT NULL,
    time_zone   VARCHAR(64)  NOT NULL,
    locale      VARCHAR(35)  NOT NULL,
    facts       JSONB        NOT NULL CHECK (jsonb_typeof(facts) = 'array'),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT business_profiles_tenant_key UNIQUE (tenant_id)
);

CREATE FUNCTION business_profile_is_visible(p_tenant_id uuid) RETURNS boolean
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

ALTER TABLE business_profiles ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON business_profiles
    FOR ALL
    USING (business_profile_is_visible(tenant_id))
    WITH CHECK (business_profile_is_visible(tenant_id));
