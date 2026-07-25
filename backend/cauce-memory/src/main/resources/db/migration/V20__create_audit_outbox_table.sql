-- V20: audit_outbox -- the capture half of the governance audit trail. An auditable action
-- writes its outbox row in the SAME transaction as the business fact (via the
-- cauce-governance AuditEventRecorder port), so capture is atomic: if the business tx rolls
-- back, no audit row survives; if it commits, the audit row is guaranteed. A background
-- drainer then moves PENDING rows, per tenant and in order, into the append-only
-- audit_log_entries ledger (V21), assigning the per-tenant sequence there.
--
-- This unit is the CONTAINER only: event_type carries no semantics yet (the real auditable
-- vocabulary and the call sites in the loop/tenancy are follow-up units) and payload is an
-- opaque jsonb document. No FK beyond tenant_id: like pending_invocations, the outbox is
-- loosely coupled to whatever the payload references. Retention of DRAINED rows is a future
-- scheduled purge (created_at is indexed via the drain index).

CREATE TABLE audit_outbox (
    id           UUID PRIMARY KEY,
    tenant_id    UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    drain_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CHECK (drain_status IN ('PENDING', 'DRAINED')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The drainer's hot path: PENDING rows of one tenant, oldest first.
CREATE INDEX idx_audit_outbox_tenant_status_created
    ON audit_outbox (tenant_id, drain_status, created_at);

-- Visibility composes tenant_is_visible (V1) directly, as in V6/V16/V19. cauce_app keeps all
-- four DML verbs here (the V10 default privileges): the drainer must UPDATE rows to DRAINED.
CREATE FUNCTION audit_outbox_is_visible(ao_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = ao_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE audit_outbox ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON audit_outbox
    FOR ALL
    USING (audit_outbox_is_visible(tenant_id))
    WITH CHECK (audit_outbox_is_visible(tenant_id));

-- Cross-tenant discovery for the drainer: which tenants have PENDING outbox rows. The drainer
-- runs without a tenant context (RLS would fail-close to nothing under cauce_app), so this is
-- the narrow SECURITY DEFINER escape hatch, mirroring V12's orphaned_invocations: it returns
-- ONLY tenant ids; the drainer then sets each tenant's context and does all further work under
-- RLS. STABLE (read-only). See ADR 0001.
CREATE FUNCTION audit_outbox_pending_tenants() RETURNS SETOF uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT DISTINCT tenant_id FROM audit_outbox WHERE drain_status = 'PENDING';
$$;

-- Escape-hatch functions are executable only by the runtime role, never PUBLIC (ADR 0001).
REVOKE EXECUTE ON FUNCTION audit_outbox_pending_tenants() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION audit_outbox_pending_tenants() TO cauce_app;
