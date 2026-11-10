-- V66: what the shop's page reports after the person's first message, and the anonymous opening
-- counter (ADR 0014, WC8, unit W7).
--
-- web_events holds a session's adds to cart from a product card and the purchases the shop reports
-- with trackPurchase. A purchase is attributed when it comes within 7 days of the session's last
-- message, by the database clock (ADR 0008); a shop reporting the same order twice records it once.
-- The session goes with its purge (session_id becomes NULL): the purchase stays a business fact.
-- Nothing about the person is here: no IP, no name, no address, only the shop's own order number.

CREATE TABLE web_events (
    id                 UUID          PRIMARY KEY,
    tenant_id          UUID          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    channel_config_id  UUID          NOT NULL REFERENCES channel_configs (id) ON DELETE RESTRICT,
    session_id         UUID          REFERENCES web_sessions (id) ON DELETE SET NULL,
    kind               VARCHAR(20)   NOT NULL,
    variant            VARCHAR(20)   NOT NULL,
    ref                VARCHAR(64),
    quantity           INTEGER,
    order_id           VARCHAR(100),
    value              NUMERIC(12, 2),
    attributed         BOOLEAN       NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT web_events_kind CHECK (kind IN ('ADD_TO_CART', 'PURCHASE')),
    CONSTRAINT web_events_shape CHECK (
        (kind = 'ADD_TO_CART' AND ref IS NOT NULL AND quantity BETWEEN 1 AND 99 AND order_id IS NULL
            AND value IS NULL AND NOT attributed)
        OR (kind = 'PURCHASE' AND order_id IS NOT NULL AND value >= 0 AND ref IS NULL AND quantity IS NULL))
);

CREATE INDEX idx_web_events_tenant ON web_events (tenant_id);
CREATE INDEX idx_web_events_channel_config ON web_events (channel_config_id);
CREATE INDEX idx_web_events_session ON web_events (session_id);
CREATE UNIQUE INDEX web_events_purchase_once ON web_events (channel_config_id, order_id) WHERE kind = 'PURCHASE';

-- The openings that end without a message, when the channel turns the counter on: aggregates only,
-- per variant and UTC day, never a session, an IP or any identifier (ADR 0014, amendment of 2026-11-09).
CREATE TABLE web_opening_counts (
    tenant_id          UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    channel_config_id  UUID         NOT NULL REFERENCES channel_configs (id) ON DELETE RESTRICT,
    day                DATE         NOT NULL,
    variant            VARCHAR(20)  NOT NULL,
    kind               VARCHAR(20)  NOT NULL,
    signal             VARCHAR(20)  NOT NULL,
    openings           BIGINT       NOT NULL,
    PRIMARY KEY (channel_config_id, day, variant, kind, signal),
    CONSTRAINT web_opening_counts_kind CHECK (kind IN ('proactive', 'on_demand')),
    CONSTRAINT web_opening_counts_positive CHECK (openings > 0)
);

CREATE INDEX idx_web_opening_counts_tenant ON web_opening_counts (tenant_id);

-- Visible exactly when their channel config is: composes channel_config_is_visible (V16).
CREATE FUNCTION web_event_is_visible(we_tenant_id uuid) RETURNS boolean
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$ SELECT channel_config_is_visible(we_tenant_id); $$;

ALTER TABLE web_events ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON web_events
    FOR ALL
    USING (web_event_is_visible(tenant_id))
    WITH CHECK (web_event_is_visible(tenant_id));

ALTER TABLE web_opening_counts ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON web_opening_counts
    FOR ALL
    USING (web_event_is_visible(tenant_id))
    WITH CHECK (web_event_is_visible(tenant_id));
