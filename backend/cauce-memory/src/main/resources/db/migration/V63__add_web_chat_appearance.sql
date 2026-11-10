-- V63: a web chat's appearance and its contact when unavailable (ADR 0014, W4). The widget has no look
-- of its own: its name, avatar, colours, typeface and texts come from here, validated by the service
-- (colour pairs at WCAG AA contrast). NULL is the platform's neutral default.

ALTER TABLE web_chat_settings ADD COLUMN appearance JSONB;

DROP FUNCTION resolve_web_chat_channel(varchar);

CREATE FUNCTION resolve_web_chat_channel(p_public_key varchar)
    RETURNS TABLE (channel_config_id uuid, tenant_id uuid, agent_id uuid, public_key varchar,
                   allowed_origins text, turnstile_site_key varchar, privacy_policy_url varchar,
                   consent_mode varchar, status varchar, sessions_per_ip_hour integer,
                   messages_per_session_minute integer, messages_per_session_day integer,
                   messages_per_ip_minute integer, reads_per_session_minute integer, appearance text)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT c.id, c.tenant_id, c.agent_id, c.public_key, array_to_string(s.allowed_origins, ' '),
           s.turnstile_site_key, s.privacy_policy_url, s.consent_mode, c.status, s.sessions_per_ip_hour,
           s.messages_per_session_minute, s.messages_per_session_day, s.messages_per_ip_minute,
           s.reads_per_session_minute, s.appearance::text
      FROM channel_configs c JOIN web_chat_settings s ON s.channel_config_id = c.id
     WHERE c.public_key = p_public_key;
$$;
