-- V37: handoffs to the owner, the owner's binding, and the owner-notification outbox (ADR 0007,
-- B7, B8, B9).
--
-- business_profiles.handoff_categories: the categories an agent may hand a case over under; empty
-- means any.
--
-- handoffs: a case an agent handed to the owner -- category, summary, how to reach the person --
-- OPEN until the owner resolves it. The conversation it came from is never moved out of OPEN.
-- The summary and the contact data are personal content: here, never in the audit chain.
--
-- owner_link_codes: single-use codes that bind an owner's chat on the owner bot (a channel config
-- of the business) to the business. Only ever compared, so stored as a SHA-256 hash; they expire.
-- owner_recipients: the binding itself, one per business: the identity and the channel config the
-- owner reads, and when they linked (the last moment they are known to have written).
--
-- owner_notifications: the outbox of proactive messages to the owner. Written in the transaction
-- of the fact it reports, sent by a scheduled dispatcher that claims due rows across tenants
-- through claim_due_owner_notifications (SECURITY DEFINER, ADR 0001) and then sends each under
-- its tenant's RLS. A claim pushes next_attempt_at forward (a lease that doubles as the backoff of
-- a failed attempt). NOT_SENT is final and carries why (outside_reply_window, no_owner_recipient,
-- channel_unavailable); FAILED is final after the attempt budget.
--
-- Hierarchical RLS keyed on tenant_id on every table.
-- No grant needed for tables: cauce_app inherits DML via the V10 default privileges.

ALTER TABLE business_profiles ADD COLUMN handoff_categories TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE business_profiles ADD CONSTRAINT business_profiles_handoff_categories_shape
    CHECK (array_position(handoff_categories, NULL) IS NULL);

CREATE TABLE handoffs (
    id              UUID PRIMARY KEY,
    tenant_id       UUID          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    code            VARCHAR(12)   NOT NULL,
    agent_id        UUID          NOT NULL REFERENCES agents (id) ON DELETE RESTRICT,
    conversation_id UUID          NOT NULL REFERENCES conversations (id) ON DELETE RESTRICT,
    identity_id     UUID          NOT NULL REFERENCES identities (id) ON DELETE RESTRICT,
    category        VARCHAR(40)   NOT NULL,
    summary         VARCHAR(2000) NOT NULL,
    contact_name    VARCHAR(100)  NOT NULL,
    contact_phone   VARCHAR(40),
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT handoffs_code_key UNIQUE (tenant_id, code),
    CONSTRAINT handoffs_resolution_shape CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

CREATE INDEX idx_handoffs_tenant ON handoffs (tenant_id, id);
CREATE INDEX idx_handoffs_open ON handoffs (tenant_id, status, created_at);

CREATE TABLE owner_link_codes (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    channel_config_id   UUID         NOT NULL REFERENCES channel_configs (id) ON DELETE RESTRICT,
    code_hash           VARCHAR(64)  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    used_at             TIMESTAMPTZ,
    used_by_identity_id UUID REFERENCES identities (id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT owner_link_codes_hash_key UNIQUE (code_hash),
    CONSTRAINT owner_link_codes_use_shape CHECK ((used_at IS NULL) = (used_by_identity_id IS NULL))
);

CREATE INDEX idx_owner_link_codes_tenant ON owner_link_codes (tenant_id);

CREATE TABLE owner_recipients (
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    channel_config_id UUID        NOT NULL REFERENCES channel_configs (id) ON DELETE RESTRICT,
    identity_id       UUID        NOT NULL REFERENCES identities (id) ON DELETE RESTRICT,
    linked_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT owner_recipients_tenant_key UNIQUE (tenant_id)
);

CREATE TABLE owner_notifications (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    kind            VARCHAR(30)  NOT NULL CHECK (kind IN ('HANDOFF', 'DAILY_DIGEST')),
    subject_id      UUID         NOT NULL,
    content         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'NOT_SENT', 'FAILED')),
    reason          VARCHAR(200),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_owner_notifications_tenant ON owner_notifications (tenant_id, id);
CREATE INDEX idx_owner_notifications_due ON owner_notifications (status, next_attempt_at);

-- Visibility: every table here is keyed on the business tenant, like whatsapp_apps (V30).
CREATE FUNCTION owner_record_is_visible(r_tenant_id uuid) RETURNS boolean
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

CREATE FUNCTION handoff_is_visible(h_tenant_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public
AS $$ SELECT owner_record_is_visible(h_tenant_id); $$;

CREATE FUNCTION owner_notification_is_visible(n_tenant_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public
AS $$ SELECT owner_record_is_visible(n_tenant_id); $$;

ALTER TABLE handoffs ENABLE ROW LEVEL SECURITY;
CREATE POLICY hierarchical_visibility ON handoffs
    FOR ALL USING (handoff_is_visible(tenant_id)) WITH CHECK (handoff_is_visible(tenant_id));

ALTER TABLE owner_link_codes ENABLE ROW LEVEL SECURITY;
CREATE POLICY hierarchical_visibility ON owner_link_codes
    FOR ALL USING (owner_record_is_visible(tenant_id)) WITH CHECK (owner_record_is_visible(tenant_id));

ALTER TABLE owner_recipients ENABLE ROW LEVEL SECURITY;
CREATE POLICY hierarchical_visibility ON owner_recipients
    FOR ALL USING (owner_record_is_visible(tenant_id)) WITH CHECK (owner_record_is_visible(tenant_id));

ALTER TABLE owner_notifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY hierarchical_visibility ON owner_notifications
    FOR ALL USING (owner_notification_is_visible(tenant_id)) WITH CHECK (owner_notification_is_visible(tenant_id));

-- claim (ADR 0001, the dispatcher's narrow cross-tenant hatch): leases up to p_batch_size due
-- PENDING notifications for p_lease_seconds and returns their ids and tenants only; the dispatcher
-- then sends each under its tenant's RLS. SKIP LOCKED keeps concurrent dispatchers disjoint.
CREATE FUNCTION claim_due_owner_notifications(p_batch_size integer, p_lease_seconds integer)
    RETURNS TABLE (id uuid, tenant_id uuid)
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    UPDATE owner_notifications n
    SET attempts        = n.attempts + 1,
        next_attempt_at = now() + make_interval(secs => p_lease_seconds),
        updated_at      = now()
    WHERE n.id IN (
        SELECT d.id FROM owner_notifications d
        WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()
        ORDER BY d.created_at ASC
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    RETURNING n.id, n.tenant_id;
$$;

REVOKE EXECUTE ON FUNCTION claim_due_owner_notifications(integer, integer) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION claim_due_owner_notifications(integer, integer) TO cauce_app;
