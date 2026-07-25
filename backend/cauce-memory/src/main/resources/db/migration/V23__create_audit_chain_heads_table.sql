-- V23: audit_chain_heads -- the single source of each tenant's chain head. One row per
-- tenant holding (last_sequence_number, last_entry_hash): the drainer reads BOTH from this
-- one row under a SELECT ... FOR UPDATE, so the next sequence number and the prev_hash can
-- never diverge (they come from a single consistent read), and updates it in the SAME
-- transaction as the ledger INSERTs and the outbox DRAINED flips. This replaces the
-- MAX(sequence_number)+1 assignment of V21's drainer; MAX survives only as the one-time
-- lazy-initialization fallback for a tenant that has no head row yet (the migration seeds
-- nothing -- there is no drained data anywhere to seed from, and inventing head state would
-- be worse than deriving it once on first drain).
--
-- The per-tenant row lock serializes drains WITHIN a tenant while leaving tenants free to
-- drain in parallel (different rows, no global coordination); V21's
-- UNIQUE (tenant_id, sequence_number) and UNIQUE (outbox_id) remain the schema-level
-- backstops. This is mutable operational state of the drainer, NOT audit data: cauce_app
-- keeps the four default DML verbs (it must INSERT and UPDATE the head). Integrity of the
-- ledger is protected by the chain itself -- an attacker rewriting the head still cannot fix
-- the entry hashes without the (future) signature failing.

CREATE TABLE audit_chain_heads (
    tenant_id            UUID PRIMARY KEY REFERENCES tenants (id) ON DELETE RESTRICT,
    last_sequence_number BIGINT       NOT NULL CHECK (last_sequence_number >= 1),
    last_entry_hash      VARCHAR(128) NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Visibility composes tenant_is_visible (V1) directly, as in V6/V16/V19/V20/V21.
CREATE FUNCTION audit_chain_head_is_visible(ach_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = ach_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

ALTER TABLE audit_chain_heads ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON audit_chain_heads
    FOR ALL
    USING (audit_chain_head_is_visible(tenant_id))
    WITH CHECK (audit_chain_head_is_visible(tenant_id));
