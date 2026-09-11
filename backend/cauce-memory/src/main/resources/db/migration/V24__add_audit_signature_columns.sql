-- V24: signature columns on the append-only audit ledger (V21). The drainer now signs each
-- v2 entry with an Ed25519 key held OUTSIDE the database, and records which key signed it.
-- Both new columns are written on the drain INSERT, so the V21 REVOKE (no UPDATE/DELETE for
-- cauce_app) stays intact; a table-level REVOKE survives ALTER TABLE ADD COLUMN.
--
-- All three signature columns are NULLABLE, and that is the contract, not an oversight:
--   * v1 entries are NEVER signed -- the capability did not exist when they were written, and
--     signing them now would be re-signing history;
--   * an instance with no signing key configured keeps running and writes unsigned entries.
--     Signing is additive, never a startup requirement.
-- The verifier therefore treats a missing signature as "not signed", never as a failure.
--
-- key_id identifies WHICH key signed, so rotation is additive: a new key gets a new key_id,
-- new entries are signed with it, and old entries keep verifying against the old public key.
-- It is a short digest of the PUBLIC key, never key material. The private key is never stored
-- here, which is precisely what makes a signed entry unforgeable by an actor holding only
-- database access.
--
-- signature_scheme names the algorithm and the signature preimage version ('ed25519-v1'), so
-- a second scheme can be added later without invalidating anything already signed -- the same
-- discipline as hash_scheme in V22.
--
-- The public key for a key_id lives in a registry published OUTSIDE the database. Keeping it
-- here would defeat the purpose: an actor who can rewrite entries could also substitute the
-- public key and have the forgeries verify.

ALTER TABLE audit_log_entries
    ADD COLUMN key_id           VARCHAR(64),
    ADD COLUMN signature_scheme VARCHAR(20);

COMMENT ON COLUMN audit_log_entries.signature IS
    'Base64 Ed25519 signature over the signature preimage; NULL for unsigned entries.';
COMMENT ON COLUMN audit_log_entries.key_id IS
    'Short digest of the signing PUBLIC key; resolved through the registry published outside the database.';
COMMENT ON COLUMN audit_log_entries.signature_scheme IS
    'Signature algorithm and preimage version, e.g. ed25519-v1.';
