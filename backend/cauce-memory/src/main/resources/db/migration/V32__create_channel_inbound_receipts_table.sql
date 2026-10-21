-- V32: receipts of inbound provider messages that are acknowledged but not ingested.
--
-- An ingested message is deduplicated by ingest_idempotency_records (V15) on the key the adapter
-- builds from the provider's message id. A message that is answered without being ingested -- a
-- WhatsApp image or audio, answered with the fixed text-only reply (ADR 0006, W5) -- produces no
-- ingest, so it needs its own guard: a redelivery must not audit it twice or reply twice. This
-- table is that guard. The UNIQUE constraint is the arbiter of an insert-first claim, in the same
-- transaction as the audit record: a concurrent redelivery blocks on it until the first commits,
-- then skips, and only the winner replies.
--
-- tenant_id is the number's tenant; hierarchical RLS keyed on it, like channel_configs (V16).
-- No grant needed: cauce_app inherits DML on new tables via the V10 default privileges.

CREATE TABLE channel_inbound_receipts (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    channel_config_id   UUID         NOT NULL REFERENCES channel_configs (id) ON DELETE RESTRICT,
    provider_message_id VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT channel_inbound_receipts_key UNIQUE (channel_config_id, provider_message_id)
);

CREATE INDEX idx_channel_inbound_receipts_tenant ON channel_inbound_receipts (tenant_id);
CREATE INDEX idx_channel_inbound_receipts_created ON channel_inbound_receipts (created_at);

CREATE FUNCTION channel_inbound_receipt_is_visible(r_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = r_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE channel_inbound_receipts ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON channel_inbound_receipts
    FOR ALL
    USING (channel_inbound_receipt_is_visible(tenant_id))
    WITH CHECK (channel_inbound_receipt_is_visible(tenant_id));
