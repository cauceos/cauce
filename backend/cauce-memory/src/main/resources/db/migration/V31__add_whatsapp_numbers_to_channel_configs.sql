-- V31: WhatsApp numbers as channel configs (ADR 0006, W2).
--
-- A WhatsApp number is a channel_config bound to the app that receives its webhooks
-- (whatsapp_app_id) and identified by Meta's phone_number_id, which routes every event the app
-- receives to its config. Two shapes of config therefore exist, told apart without naming a
-- channel (the channel list is SPI-owned, never a CHECK):
--   * per-config webhook (Telegram): a webhook_secret_hash, no app binding;
--   * app-routed (WhatsApp): an app and a phone_number_id, no webhook secret of its own.
-- phone_number_id is unique across the instance: one number is one door.
--
-- credential becomes TEXT: since W3 it holds a sealed value, longer than the plaintext, and a
-- WhatsApp access token alone can approach the old 512 limit.

ALTER TABLE channel_configs ALTER COLUMN credential TYPE TEXT;
ALTER TABLE channel_configs ALTER COLUMN webhook_secret_hash DROP NOT NULL;

ALTER TABLE channel_configs
    ADD COLUMN whatsapp_app_id UUID REFERENCES whatsapp_apps (id) ON DELETE RESTRICT,
    ADD COLUMN phone_number_id VARCHAR(64);

ALTER TABLE channel_configs ADD CONSTRAINT channel_configs_routing_shape CHECK (
    (whatsapp_app_id IS NULL AND phone_number_id IS NULL AND webhook_secret_hash IS NOT NULL)
    OR (whatsapp_app_id IS NOT NULL AND phone_number_id IS NOT NULL AND webhook_secret_hash IS NULL)
);

CREATE UNIQUE INDEX idx_channel_configs_phone_number_id
    ON channel_configs (phone_number_id) WHERE phone_number_id IS NOT NULL;
CREATE INDEX idx_channel_configs_whatsapp_app ON channel_configs (whatsapp_app_id);

-- SECURITY DEFINER routing for the WhatsApp webhook (ADR 0001, pattern 2), called only after the
-- request's signature has verified under the app's secret. It resolves the ACTIVE number of
-- exactly that app: a phone_number_id bound to another app is not routed, so one app cannot
-- inject events into a number it does not serve.
CREATE FUNCTION resolve_whatsapp_number(p_app_id uuid, p_phone_number_id varchar)
    RETURNS SETOF channel_configs
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT * FROM channel_configs
    WHERE whatsapp_app_id = p_app_id AND phone_number_id = p_phone_number_id AND status = 'ACTIVE';
$$;
