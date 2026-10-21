-- V35: questions an agent could not answer (ADR 0007, B4).
--
-- Recorded by a tool when the profile or the catalog does not hold the answer; the owner reads
-- them in the daily digest. The question is the customer's words: erasable business content, so
-- it lives here and never enters the audit chain in clear. tenant_id is the agent's tenant.
-- Hierarchical RLS keyed on tenant_id. Indexed for the per-tenant listing (keyset on id) and for
-- the digest's "since the previous digest" window (created_at).
-- No grant needed: cauce_app inherits DML on new tables via the V10 default privileges.

CREATE TABLE knowledge_gaps (
    id              UUID PRIMARY KEY,
    tenant_id       UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    agent_id        UUID        NOT NULL REFERENCES agents (id) ON DELETE RESTRICT,
    conversation_id UUID        NOT NULL REFERENCES conversations (id) ON DELETE RESTRICT,
    question        TEXT        NOT NULL CHECK (length(question) BETWEEN 1 AND 1000),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_gaps_tenant ON knowledge_gaps (tenant_id, id);
CREATE INDEX idx_knowledge_gaps_created ON knowledge_gaps (tenant_id, created_at);

CREATE FUNCTION knowledge_gap_is_visible(g_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = g_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE knowledge_gaps ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON knowledge_gaps
    FOR ALL
    USING (knowledge_gap_is_visible(tenant_id))
    WITH CHECK (knowledge_gap_is_visible(tenant_id));
