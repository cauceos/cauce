-- V1: tenants table with multi-tier hierarchy and Row-Level Security.
--
-- TODO: when application endpoints and authentication are introduced, introduce a
-- least-privilege role (cauce_app) that connects to this database without SUPERUSER
-- privileges, so RLS policies enforce tenant isolation at runtime. Currently, RLS is
-- enabled but the application connects as superuser in development, which bypasses
-- RLS. RLS is verified end-to-end only in integration tests using a dedicated
-- restricted role.

CREATE TABLE tenants (
    id               UUID PRIMARY KEY,
    parent_tenant_id UUID REFERENCES tenants (id) ON DELETE RESTRICT,
    tier             VARCHAR(20)  NOT NULL CHECK (tier IN ('OPERATOR', 'PARTNER', 'CLIENT')),
    name             VARCHAR(200) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- An OPERATOR has no parent; a PARTNER or CLIENT must have one.
    CONSTRAINT tenant_parent_shape CHECK (
        (tier =  'OPERATOR' AND parent_tenant_id IS NULL) OR
        (tier <> 'OPERATOR' AND parent_tenant_id IS NOT NULL)
    )
);

CREATE INDEX idx_tenants_parent ON tenants (parent_tenant_id);
CREATE INDEX idx_tenants_tier ON tenants (tier);

-- The parent-tier rule (a PARTNER's parent must be an OPERATOR, a CLIENT's parent
-- must be a PARTNER) cannot be a CHECK constraint, since CHECK cannot query other
-- rows. A trigger enforces it. SECURITY DEFINER so the lookup is not blocked by RLS.
CREATE FUNCTION enforce_tenant_hierarchy() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    parent_tier varchar(20);
BEGIN
    IF NEW.tier = 'OPERATOR' THEN
        RETURN NEW; -- shape CHECK already guarantees a null parent
    END IF;

    SELECT tier INTO parent_tier FROM tenants WHERE id = NEW.parent_tenant_id;
    IF parent_tier IS NULL THEN
        RAISE EXCEPTION 'parent tenant % does not exist', NEW.parent_tenant_id;
    END IF;

    IF NEW.tier = 'PARTNER' AND parent_tier <> 'OPERATOR' THEN
        RAISE EXCEPTION 'a PARTNER parent must be an OPERATOR (was %)', parent_tier;
    END IF;
    IF NEW.tier = 'CLIENT' AND parent_tier <> 'PARTNER' THEN
        RAISE EXCEPTION 'a CLIENT parent must be a PARTNER (was %)', parent_tier;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_tenant_hierarchy
    BEFORE INSERT OR UPDATE ON tenants
    FOR EACH ROW
    EXECUTE FUNCTION enforce_tenant_hierarchy();

-- The tenant context is set per transaction with:
--   SET LOCAL app.current_tenant_id = '<uuid>'
CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql
    STABLE
AS $$
    SELECT NULLIF(current_setting('app.current_tenant_id', true), '')::uuid;
$$;

-- Visibility: a row is visible to the current tenant C if it is C itself, a direct
-- child of C, or a grandchild (a client of one of C's partners). SECURITY DEFINER
-- so the self-referencing lookup bypasses RLS and does not recurse.
CREATE FUNCTION tenant_is_visible(row_id uuid, row_parent uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    c uuid := current_tenant_id();
BEGIN
    IF c IS NULL THEN
        RETURN false; -- no tenant context => deny everything (defense in depth)
    END IF;
    IF row_id = c OR row_parent = c THEN
        RETURN true; -- self or direct child
    END IF;
    RETURN EXISTS (
        SELECT 1 FROM tenants p WHERE p.id = row_parent AND p.parent_tenant_id = c
    ); -- grandchild: a client of one of my partners
END;
$$;

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_visibility ON tenants
    FOR ALL
    USING (tenant_is_visible(id, parent_tenant_id))
    WITH CHECK (tenant_is_visible(id, parent_tenant_id));
