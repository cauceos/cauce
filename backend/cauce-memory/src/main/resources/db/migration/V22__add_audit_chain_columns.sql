-- V22: hash-chain columns on the append-only audit ledger (V21). The drainer now computes,
-- per tenant, a hash chain over the entries it inserts: entry_hash covers the entry's own
-- fields plus prev_hash and payload_hash -- NEVER the raw payload. Both new columns are
-- written on the drain INSERT, so the V21 REVOKE (no UPDATE/DELETE for cauce_app) stays
-- intact; a table-level REVOKE survives ALTER TABLE ADD COLUMN.
--
-- payload_hash is PERSISTED, not derived: verification after an erasure needs the hash even
-- when the raw payload is gone. That is the erasure-compatibility design decision made at
-- record one -- chaining the raw payload instead would force re-hashing the whole chain the
-- day redaction arrives. Rows drained before this migration (none exist in any real
-- database: the recorder has no production callers yet) keep NULL hashes -- they are honest
-- pre-chain rows, never backfilled with invented values.
--
-- hash_scheme identifies the algorithm/preimage version ('v1' = SHA-256 over the canonical
-- JSON preimage defined in AuditChainHasher) so the scheme can evolve row by row and the
-- future signature has a stable target (entry_hash + scheme).
--
-- payload becomes nullable: setting it to NULL is the post-hoc redaction hook. cauce_app
-- cannot UPDATE this table, so redaction is by construction a privileged, deliberate owner
-- operation -- and the chain still verifies through the stored payload_hash. This is a
-- TECHNICAL capability (the chain verifies without the original content); whether it
-- satisfies a legal erasure obligation is the customer's call, never asserted here.

ALTER TABLE audit_log_entries
    ADD COLUMN payload_hash VARCHAR(128),
    ADD COLUMN hash_scheme  VARCHAR(20),
    ALTER COLUMN payload DROP NOT NULL;
