-- V3: conversations table. Each conversation belongs to an agent, which belongs to a
-- CLIENT tenant. Visibility is therefore inherited transitively: a conversation is
-- visible exactly when its agent is, which holds exactly when the agent's tenant is.
--
-- RLS NOTE: same as V1/V2 — RLS is enabled here, but the development application user
-- is a PostgreSQL superuser and bypasses RLS; enforcement is exercised in integration
-- tests via a dedicated least-privilege role.
--   TODO: when application endpoints and authentication are introduced, connect the
--   application as a least-privilege role (cauce_app) without SUPERUSER so RLS enforces
--   tenant isolation at runtime.
--
-- channel_type is a free-form identifier validated by the application service against a
-- temporary known set (to be replaced by the cauce-channels channel registry when the
-- SPI exists); it intentionally has no CHECK constraint so the database does not
-- hardcode the channel list. status is a closed domain enum and keeps its CHECK.

CREATE TABLE conversations (
    id                    UUID PRIMARY KEY,
    agent_id              UUID NOT NULL REFERENCES agents (id) ON DELETE RESTRICT,
    channel_type          VARCHAR(20)  NOT NULL,
    external_identity_ref VARCHAR(320) NOT NULL,
    status                VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'ESCALATED', 'ARCHIVED')),
    started_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_message_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at             TIMESTAMPTZ
);

CREATE INDEX idx_conversations_agent ON conversations (agent_id);
CREATE INDEX idx_conversations_status ON conversations (status);
-- "active conversations of this agent" — the common operational query.
CREATE INDEX idx_conversations_agent_status ON conversations (agent_id, status);
-- cross-channel identity resolution — locating a user's threads by channel reference.
CREATE INDEX idx_conversations_identity ON conversations (channel_type, external_identity_ref);

-- A conversation is visible exactly when its agent is visible to the current tenant
-- context. Composes with agent_is_visible (V2), which composes with tenant_is_visible
-- (V1). SECURITY DEFINER so the agent lookup bypasses RLS (no recursion against
-- conversations).
CREATE FUNCTION conversation_is_visible(conv_agent_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    a_tenant uuid;
BEGIN
    SELECT tenant_id INTO a_tenant FROM agents WHERE id = conv_agent_id;
    IF a_tenant IS NULL THEN
        RETURN false;
    END IF;
    RETURN agent_is_visible(a_tenant);
END;
$$;

ALTER TABLE conversations ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON conversations
    FOR ALL
    USING (conversation_is_visible(agent_id))
    WITH CHECK (conversation_is_visible(agent_id));
