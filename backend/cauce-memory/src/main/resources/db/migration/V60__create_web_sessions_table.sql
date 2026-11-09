-- V60: anonymous web-chat sessions (ADR 0014, W2).
--
-- A browser opens a session with a valid Turnstile token and gets a random token back, once; only
-- its hash is stored (token_hash, by the same ApiKeyHasher as every value only ever compared). The
-- session is the person: its id is the value of their SESSION_ID identity on channel web_chat,
-- created with their first message. It lasts 30 days, sliding: expires_at moves with each
-- authenticated request, by the database clock (ADR 0008). No IP is ever stored (ADR 0014,
-- amendment). With explicit acceptance (WC5), consented_at and a hash of the notice it was given
-- for are recorded.

CREATE TABLE web_sessions (
    id                   UUID         PRIMARY KEY,
    tenant_id            UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    channel_config_id    UUID         NOT NULL REFERENCES channel_configs (id) ON DELETE RESTRICT,
    token_hash           VARCHAR(255) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ  NOT NULL,
    consented_at         TIMESTAMPTZ,
    consent_notice_hash  VARCHAR(64),
    CONSTRAINT web_sessions_token_hash_key UNIQUE (token_hash),
    CONSTRAINT web_sessions_consent_shape CHECK ((consented_at IS NULL) = (consent_notice_hash IS NULL))
);

CREATE INDEX idx_web_sessions_tenant ON web_sessions (tenant_id);
CREATE INDEX idx_web_sessions_channel_config ON web_sessions (channel_config_id);
CREATE INDEX idx_web_sessions_expires ON web_sessions (expires_at);

-- Visible exactly when its channel config is: composes channel_config_is_visible (V16).
CREATE FUNCTION web_session_is_visible(ws_tenant_id uuid) RETURNS boolean
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$ SELECT channel_config_is_visible(ws_tenant_id); $$;

ALTER TABLE web_sessions ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON web_sessions
    FOR ALL
    USING (web_session_is_visible(tenant_id))
    WITH CHECK (web_session_is_visible(tenant_id));

-- SECURITY DEFINER resolution of a session by its token's hash (ADR 0001, pattern 2): a browser's
-- request carries the token and no tenant context. Only an unexpired session, and only what binding
-- the request needs; everything after runs under RLS in the session's tenant context.
CREATE FUNCTION resolve_web_session(p_token_hash varchar)
    RETURNS TABLE (id uuid, tenant_id uuid, channel_config_id uuid, expires_at timestamptz)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT s.id, s.tenant_id, s.channel_config_id, s.expires_at
      FROM web_sessions s
     WHERE s.token_hash = p_token_hash AND s.expires_at > now();
$$;

-- The purge of expired sessions across tenants (ADR 0001): a scheduled job with no tenant context.
-- It deletes only what has expired, by the database clock, and returns how many.
CREATE FUNCTION purge_expired_web_sessions()
    RETURNS integer
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    purged integer;
BEGIN
    DELETE FROM web_sessions WHERE expires_at <= now();
    GET DIAGNOSTICS purged = ROW_COUNT;
    RETURN purged;
END;
$$;
