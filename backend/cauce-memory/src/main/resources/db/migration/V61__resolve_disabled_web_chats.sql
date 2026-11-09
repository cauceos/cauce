-- V61: the emergency switch of a web chat (ADR 0014, amendment of 2026-11-09).
--
-- A disabled web chat must answer its widget with a clear error rather than look like an unknown key:
-- the public surface resolves it too, now with its status, and refuses its new sessions and messages
-- itself. An unknown key still resolves to nothing. The credential is still never returned.

DROP FUNCTION resolve_web_chat_channel(varchar);

CREATE FUNCTION resolve_web_chat_channel(p_public_key varchar)
    RETURNS TABLE (channel_config_id uuid, tenant_id uuid, agent_id uuid, public_key varchar,
                   allowed_origins text, turnstile_site_key varchar, privacy_policy_url varchar,
                   consent_mode varchar, status varchar)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT c.id, c.tenant_id, c.agent_id, c.public_key, array_to_string(s.allowed_origins, ' '),
           s.turnstile_site_key, s.privacy_policy_url, s.consent_mode, c.status
      FROM channel_configs c JOIN web_chat_settings s ON s.channel_config_id = c.id
     WHERE c.public_key = p_public_key;
$$;
