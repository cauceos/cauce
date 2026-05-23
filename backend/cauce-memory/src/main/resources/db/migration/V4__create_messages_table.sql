-- V4: messages table. Each message belongs to a conversation, which belongs to an
-- agent, which belongs to a CLIENT tenant. Visibility is therefore inherited
-- transitively: a message is visible exactly when its conversation is.
--
-- RLS NOTE: same as V1-V3 -- RLS is enabled here, but the development application user
-- is a PostgreSQL superuser and bypasses RLS; enforcement is exercised in integration
-- tests via a dedicated least-privilege role.
--   TODO: when application endpoints and authentication are introduced, connect the
--   application as a least-privilege role (cauce_app) without SUPERUSER so RLS enforces
--   tenant isolation at runtime.
--
-- role is a closed domain enum and keeps its CHECK. content is opaque UTF-8 text,
-- stored as TEXT and validated (non-blank) by the application service / domain factory.

CREATE TABLE messages (
    id              UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE RESTRICT,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'AGENT', 'SYSTEM')),
    content         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index on conversation_id alone is technically redundant with the composite
-- (conversation_id, created_at) below for query plans, but kept as a safety net for
-- index maintenance scenarios.
CREATE INDEX idx_messages_conversation ON messages (conversation_id);
-- Chronological ordering of a conversation's messages.
CREATE INDEX idx_messages_conversation_created ON messages (conversation_id, created_at);

-- A message is visible exactly when its conversation is visible to the current tenant
-- context. Composes with conversation_is_visible (V3), which composes with
-- agent_is_visible (V2) and tenant_is_visible (V1). SECURITY DEFINER so the conversation
-- lookup bypasses RLS (no recursion against messages).
CREATE FUNCTION message_is_visible(msg_conversation_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    c_agent uuid;
BEGIN
    SELECT agent_id INTO c_agent FROM conversations WHERE id = msg_conversation_id;
    IF c_agent IS NULL THEN
        RETURN false;
    END IF;
    RETURN conversation_is_visible(c_agent);
END;
$$;

ALTER TABLE messages ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON messages
    FOR ALL
    USING (message_is_visible(conversation_id))
    WITH CHECK (message_is_visible(conversation_id));
