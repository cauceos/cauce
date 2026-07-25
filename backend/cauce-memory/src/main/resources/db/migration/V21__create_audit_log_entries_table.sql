-- V21: audit_log_entries -- the append-only destination of the audit trail. The governance
-- drainer moves audit_outbox rows (V20) here per tenant, assigning a contiguous per-tenant
-- sequence_number in the same transaction that marks the outbox row DRAINED.
--
-- APPEND-ONLY BY ROLE, not by code discipline: the REVOKE at the bottom strips UPDATE and
-- DELETE from cauce_app, so the runtime role can only SELECT and INSERT -- Postgres itself
-- rejects any mutation of a written entry (TRUNCATE was never granted; V10 grants only the
-- four DML verbs). Mechanism: the CREATE TABLE above fires V10's ALTER DEFAULT PRIVILEGES
-- (keyed to the Flyway owner role) which grants SELECT/INSERT/UPDATE/DELETE at creation time;
-- the surgical REVOKE below then removes exactly UPDATE and DELETE -- the same pattern V10
-- itself uses on flyway_schema_history. The owner keeps full DML (schema operations, test
-- TRUNCATEs); the runtime actor is cauce_app and that is the one capped.
--
-- Reserved-for-the-chain-unit columns (created now so the ledger shape is final, filled by a
-- follow-up unit, NULL until then): prev_hash / entry_hash (per-tenant hash chain over the
-- entries) and signature (the signing slot). This unit only moves and numbers.
--
-- UNIQUE (tenant_id, sequence_number): the per-tenant chain is anchored in the schema -- a
-- concurrent drain of the same tenant becomes a constraint violation + retry, never a
-- duplicated sequence. UNIQUE (outbox_id): the same outbox row can never be drained twice
-- (idempotency at the schema layer) and links entry back to its capture row. No FK on
-- outbox_id: the ledger must outlive a future purge of DRAINED outbox rows.

CREATE TABLE audit_log_entries (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    sequence_number BIGINT       NOT NULL CHECK (sequence_number >= 1),
    outbox_id       UUID         NOT NULL UNIQUE,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    drained_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Reserved for the hash-chain unit; never written by this unit.
    prev_hash       VARCHAR(128),
    entry_hash      VARCHAR(128),
    signature       TEXT,
    CONSTRAINT audit_log_entries_tenant_sequence UNIQUE (tenant_id, sequence_number)
);

-- Visibility composes tenant_is_visible (V1) directly, as in V6/V16/V19/V20.
CREATE FUNCTION audit_log_entry_is_visible(al_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = al_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE audit_log_entries ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON audit_log_entries
    FOR ALL
    USING (audit_log_entry_is_visible(tenant_id))
    WITH CHECK (audit_log_entry_is_visible(tenant_id));

-- THE core of this unit: append-only enforced at the privilege layer. cauce_app keeps
-- SELECT + INSERT exactly.
REVOKE UPDATE, DELETE ON audit_log_entries FROM cauce_app;
