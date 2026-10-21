-- V34: the business catalog (ADR 0007, B1, B4, B5).
--
-- One per business tenant, replaced as a whole: the items as a JSON array (a bar's menu is tens
-- of items, read whole by the agent's search), and the attributes every item must state. For a
-- declared attribute, an item without it is reported NOT_DECLARED, never "none" -- the rule that
-- keeps an agent from inventing allergens. The database checks only the shapes; the items are
-- validated by the domain.
-- Hierarchical RLS keyed on tenant_id, like whatsapp_apps (V30).
-- No grant needed: cauce_app inherits DML on new tables via the V10 default privileges.

CREATE TABLE catalogs (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    declared_attributes TEXT[]      NOT NULL DEFAULT '{}',
    items               JSONB       NOT NULL CHECK (jsonb_typeof(items) = 'array'),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT catalogs_tenant_key UNIQUE (tenant_id),
    CONSTRAINT catalogs_declared_attributes_shape CHECK (array_position(declared_attributes, NULL) IS NULL)
);

CREATE FUNCTION catalog_is_visible(c_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = c_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE catalogs ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON catalogs
    FOR ALL
    USING (catalog_is_visible(tenant_id))
    WITH CHECK (catalog_is_visible(tenant_id));
