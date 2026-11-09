-- V59: web-chat channels for online shops (ADR 0014, W1).
--
-- A web-chat channel is a channel_config of a third shape, routed by its public key: a browser
-- reaches it through /web/v1/{public_key}/..., with no webhook secret and no app. The key is not a
-- secret (it is embedded in the shop's page) and allows only what the channel does: open a session,
-- send to its agent, read that session's messages. credential holds the sealed Turnstile secret.
-- The three shapes are told apart without naming a channel (the channel list is SPI-owned).

ALTER TABLE channel_configs ADD COLUMN public_key VARCHAR(40);

CREATE UNIQUE INDEX idx_channel_configs_public_key ON channel_configs (public_key) WHERE public_key IS NOT NULL;

ALTER TABLE channel_configs DROP CONSTRAINT channel_configs_routing_shape;
ALTER TABLE channel_configs ADD CONSTRAINT channel_configs_routing_shape CHECK (
    (whatsapp_app_id IS NULL AND phone_number_id IS NULL AND public_key IS NULL AND webhook_secret_hash IS NOT NULL)
    OR (whatsapp_app_id IS NOT NULL AND phone_number_id IS NOT NULL AND public_key IS NULL
        AND webhook_secret_hash IS NULL)
    OR (public_key IS NOT NULL AND whatsapp_app_id IS NULL AND phone_number_id IS NULL
        AND webhook_secret_hash IS NULL)
);

-- What a web-chat channel needs besides the binding: the origins a browser may call it from (exact
-- scheme://host[:port]), the Turnstile site key the widget shows, and the privacy settings (WC5).
-- One row per web-chat config, owned by the config's tenant.
CREATE TABLE web_chat_settings (
    channel_config_id   UUID         PRIMARY KEY REFERENCES channel_configs (id) ON DELETE RESTRICT,
    tenant_id           UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    allowed_origins     TEXT[]       NOT NULL CHECK (cardinality(allowed_origins) BETWEEN 1 AND 20),
    turnstile_site_key  VARCHAR(100) NOT NULL,
    privacy_policy_url  VARCHAR(500) NOT NULL,
    consent_mode        VARCHAR(20)  NOT NULL CHECK (consent_mode IN ('NOTICE', 'ACCEPTANCE')),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_web_chat_settings_tenant ON web_chat_settings (tenant_id);

-- Visible exactly when its channel config is: composes channel_config_is_visible (V16).
CREATE FUNCTION web_chat_settings_is_visible(s_tenant_id uuid) RETURNS boolean
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$ SELECT channel_config_is_visible(s_tenant_id); $$;

ALTER TABLE web_chat_settings ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON web_chat_settings
    FOR ALL
    USING (web_chat_settings_is_visible(tenant_id))
    WITH CHECK (web_chat_settings_is_visible(tenant_id));

-- SECURITY DEFINER resolution for the public surface (ADR 0001, pattern 2): a browser's request
-- carries a public key and no tenant context, so this lookup is what discovers the tenant. Minimal:
-- the ACTIVE web-chat channel with exactly that key, and only what deciding the request needs (its
-- ids, origins and public settings), never the credential. Everything after it runs under RLS.
CREATE FUNCTION resolve_web_chat_channel(p_public_key varchar)
    RETURNS TABLE (channel_config_id uuid, tenant_id uuid, agent_id uuid, public_key varchar,
                   allowed_origins text, turnstile_site_key varchar, privacy_policy_url varchar,
                   consent_mode varchar)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT c.id, c.tenant_id, c.agent_id, c.public_key, array_to_string(s.allowed_origins, ' '),
           s.turnstile_site_key, s.privacy_policy_url, s.consent_mode
      FROM channel_configs c JOIN web_chat_settings s ON s.channel_config_id = c.id
     WHERE c.public_key = p_public_key AND c.status = 'ACTIVE';
$$;
