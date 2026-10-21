-- V30: the WhatsApp app record (ADR 0006, W2).
--
-- Meta configures one webhook URL per app, and one app may serve numbers bound to agents of
-- several tenants (a partner's app for its clients). So the app is its own entity, owned by a
-- tenant, and the webhook URL names it: /webhooks/whatsapp/{id}. It holds:
--   * meta_app_id: Meta's own id of the app, informational.
--   * app_secret: the key Meta signs every webhook POST with (X-Hub-Signature-256). Needed as
--     itself to verify an HMAC, so it is SEALED (AES-GCM, W3), never hashed and never in clear.
--     TEXT: a sealed value is longer than its plaintext.
--   * verify_token_hash: hash of the server-generated token Meta echoes on the subscription GET.
--     Only ever compared, so hashed, like webhook secrets and API keys.
-- Hierarchical RLS keyed on tenant_id, exactly like channel_configs (V16).
-- No grant needed: cauce_app inherits DML on new tables via the V10 default privileges.

CREATE TABLE whatsapp_apps (
    id                UUID PRIMARY KEY,
    tenant_id         UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    meta_app_id       VARCHAR(64)  NOT NULL,
    app_secret        TEXT         NOT NULL,
    verify_token_hash VARCHAR(255) NOT NULL,
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_whatsapp_apps_tenant ON whatsapp_apps (tenant_id);

-- Visible exactly when the owning tenant is visible (mirrors channel_config_is_visible, V16).
CREATE FUNCTION whatsapp_app_is_visible(app_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = app_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE whatsapp_apps ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON whatsapp_apps
    FOR ALL
    USING (whatsapp_app_is_visible(tenant_id))
    WITH CHECK (whatsapp_app_is_visible(tenant_id));

-- SECURITY DEFINER resolution for the webhook path (ADR 0001, pattern 2; mirrors
-- resolve_active_channel_config, V16). The webhook carries no tenant context; this is what
-- finds the app -- and its sealed secret -- from the URL. Exposing the row is safe: the secret
-- is sealed under a key the database does not hold, and an app id authenticates nobody; the
-- webhook is trusted only after its signature verifies under the opened secret.
CREATE FUNCTION resolve_active_whatsapp_app(p_app_id uuid)
    RETURNS SETOF whatsapp_apps
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT * FROM whatsapp_apps
    WHERE id = p_app_id AND status = 'ACTIVE';
$$;
