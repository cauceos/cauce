-- V36: the agenda of a business and its bookings (ADR 0007, B6).
--
-- agendas: one per business tenant. The shifts (id, days, start, end, capacity) are a JSON array
-- validated by the domain; closed dates a DATE[]. The row is also the lock every booking write
-- takes (SELECT ... FOR UPDATE) before counting capacity, so concurrent proposals for the last
-- places of a shift are serialized and never overbook.
--
-- bookings: one shift on one date for one interlocutor (identity_id), who alone may change or
-- cancel it. Every change is a proposal confirmed in a later turn: proposed_in_message_id is the
-- trigger message of the proposal, and the confirmation is refused from that same message.
--   * HELD holds capacity until hold_expires_at; CONFIRMED holds it; CANCELLED does not.
--   * A change is a new HELD booking with replaces_booking_id; confirming it cancels the old one.
--   * A cancellation is requested on the CONFIRMED booking itself (cancellation_* columns).
-- code is the short reference people use, unique per business. contact_name, contact_phone and
-- notes are personal data: they live here, where deletion operates, never in the audit chain.
-- Hierarchical RLS keyed on tenant_id on both tables.
-- No grant needed: cauce_app inherits DML on new tables via the V10 default privileges.

CREATE TABLE agendas (
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    unit           VARCHAR(40) NOT NULL,
    max_party_size INTEGER     NOT NULL CHECK (max_party_size >= 1),
    hold_minutes   INTEGER     NOT NULL CHECK (hold_minutes BETWEEN 1 AND 1440),
    horizon_days   INTEGER     NOT NULL CHECK (horizon_days BETWEEN 0 AND 730),
    require_phone  BOOLEAN     NOT NULL,
    shifts         JSONB       NOT NULL CHECK (jsonb_typeof(shifts) = 'array'),
    closed_dates   DATE[]      NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agendas_tenant_key UNIQUE (tenant_id)
);

CREATE TABLE bookings (
    id                                  UUID PRIMARY KEY,
    tenant_id                           UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    code                                VARCHAR(12)  NOT NULL,
    agent_id                            UUID         NOT NULL REFERENCES agents (id) ON DELETE RESTRICT,
    conversation_id                     UUID         NOT NULL REFERENCES conversations (id) ON DELETE RESTRICT,
    identity_id                         UUID         NOT NULL REFERENCES identities (id) ON DELETE RESTRICT,
    service_date                        DATE         NOT NULL,
    shift_id                            VARCHAR(40)  NOT NULL,
    party_size                          INTEGER      NOT NULL CHECK (party_size >= 1),
    contact_name                        VARCHAR(100) NOT NULL,
    contact_phone                       VARCHAR(40),
    notes                               VARCHAR(500),
    status                              VARCHAR(20)  NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED')),
    replaces_booking_id                 UUID REFERENCES bookings (id) ON DELETE RESTRICT,
    proposed_in_message_id              UUID         NOT NULL,
    hold_expires_at                     TIMESTAMPTZ  NOT NULL,
    cancellation_requested_in_message_id UUID,
    cancellation_expires_at             TIMESTAMPTZ,
    created_at                          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT bookings_code_key UNIQUE (tenant_id, code),
    CONSTRAINT bookings_cancellation_shape CHECK (
        (cancellation_requested_in_message_id IS NULL) = (cancellation_expires_at IS NULL))
);

-- Capacity counting and the owner's day view; the interlocutor's own bookings.
CREATE INDEX idx_bookings_slot ON bookings (tenant_id, service_date, shift_id, status);
CREATE INDEX idx_bookings_identity ON bookings (tenant_id, identity_id, service_date);
CREATE INDEX idx_bookings_tenant ON bookings (tenant_id, id);

CREATE FUNCTION agenda_is_visible(a_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = a_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

-- A booking is visible exactly when its business's agenda would be: same tenant rule.
CREATE FUNCTION booking_is_visible(b_tenant_id uuid) RETURNS boolean
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT agenda_is_visible(b_tenant_id);
$$;

ALTER TABLE agendas ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON agendas
    FOR ALL
    USING (agenda_is_visible(tenant_id))
    WITH CHECK (agenda_is_visible(tenant_id));

ALTER TABLE bookings ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON bookings
    FOR ALL
    USING (booking_is_visible(tenant_id))
    WITH CHECK (booking_is_visible(tenant_id));
