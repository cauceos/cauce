-- V15: ingest_idempotency_records table -- deduplication of inbound message ingestion.
-- Each row records one accepted ingest keyed by (agent_id, idempotency_key): a retried or
-- redelivered request with the same key (at-least-once channel webhooks, client retries)
-- returns the stored result instead of appending a second USER message and enqueueing a
-- second invocation.
--
-- The UNIQUE constraint is the ON CONFLICT arbiter for the insert-first locking scheme in
-- InboundMessageService: the row is inserted as a lock (result columns NULL) at the start
-- of the work, and completed with the result ids in the same transaction. A concurrent
-- duplicate blocks on the unique index until the original commits, then reads the completed
-- row. A COMMITTED row is therefore always complete -- the NULL state exists only inside
-- the transaction that holds the lock; the result_shape CHECK defends that invariant in
-- depth. conversation_id / message_id / invocation_id are intentionally NOT foreign keys
-- (same loose coupling as pending_invocations): the record is bookkeeping, and must not
-- forbid deleting the data it points at.
--
-- Retention is deferred: created_at plus its index make a future scheduled purge
-- (DELETE WHERE created_at < ...) a pure addition.
--
-- No grant needed: cauce_app inherits DML on new tables via the V10 ALTER DEFAULT
-- PRIVILEGES. The idempotency key is an opaque, caller-chosen string (future channel
-- adapters use the provider's message id), so it has no format CHECK.

CREATE TABLE ingest_idempotency_records (
    id              UUID PRIMARY KEY,
    agent_id        UUID         NOT NULL REFERENCES agents (id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(255) NOT NULL,
    conversation_id UUID,
    message_id      UUID,
    invocation_id   UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ingest_idempotency_records_key UNIQUE (agent_id, idempotency_key),
    -- Either the in-transaction lock state (all NULL) or a complete result (all set).
    CONSTRAINT ingest_idempotency_records_result_shape CHECK (
        (conversation_id IS NULL AND message_id IS NULL AND invocation_id IS NULL)
        OR (conversation_id IS NOT NULL AND message_id IS NOT NULL AND invocation_id IS NOT NULL))
);

-- Future retention purge scans by age; the unique constraint above already serves the
-- (agent_id, idempotency_key) lookup hot path.
CREATE INDEX idx_ingest_idempotency_created ON ingest_idempotency_records (created_at);

-- An idempotency record is visible exactly when its agent is visible to the current tenant
-- context. Mirrors conversation_is_visible (V3): composes with agent_is_visible (V2), which
-- composes with tenant_is_visible (V1). SECURITY DEFINER so the agent lookup bypasses RLS
-- (no recursion against ingest_idempotency_records).
CREATE FUNCTION ingest_idempotency_record_is_visible(rec_agent_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    a_tenant uuid;
BEGIN
    SELECT tenant_id INTO a_tenant FROM agents WHERE id = rec_agent_id;
    IF a_tenant IS NULL THEN
        RETURN false;
    END IF;
    RETURN agent_is_visible(a_tenant);
END;
$$;

ALTER TABLE ingest_idempotency_records ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON ingest_idempotency_records
    FOR ALL
    USING (ingest_idempotency_record_is_visible(agent_id))
    WITH CHECK (ingest_idempotency_record_is_visible(agent_id));
