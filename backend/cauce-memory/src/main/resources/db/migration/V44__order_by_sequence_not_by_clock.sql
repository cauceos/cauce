-- V44: the order of a conversation, and of the audit outbox, never depends on a clock.
--
-- Messages were read ORDER BY created_at, a clock_timestamp() (ADR 0008). A wall clock can step
-- back (an NTP correction, a VM resuming), and then a later message sorted before an earlier one:
-- on 2026-10-23 a conversation's "Sí" sorted before the agent's previous reply, the request to the
-- model ended on an assistant message and the provider refused it (HTTP 400, no prefill), so the
-- person got the fallback reply. created_at stays: it is a time, for the windows that compare
-- times; it no longer orders anything.
--
-- 1. Each message gets seq, its position in its conversation: 1, 2, 3… without gaps. A BEFORE
--    INSERT trigger takes it from conversations.last_message_seq with UPDATE … RETURNING, so the
--    conversation's row lock serialises the appends of one conversation and the order of seq is
--    the order of commits. Every insert path gets it, including the ones that bypass the JPA
--    mapping (tests, the eval harness).
-- 2. The audit outbox gets seq from an identity: the drainer chains a tenant's rows in that
--    order, so the chain follows the order the facts were written, not their clock.
--
-- Reviewed and left as they are: the claims of pending_invocations and owner_notifications order
-- by created_at only to pick which due rows go first. Their processing order is not guaranteed
-- anyway (a parallel executor; an UPDATE … RETURNING returns rows in no defined order), so a
-- clock step changes who goes first, never what is done. Knowledge gaps, digests and reply
-- windows compare times, which is what created_at is for.

-- 1. Messages.
ALTER TABLE conversations ADD COLUMN last_message_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN seq BIGINT;

-- Existing rows keep the order they were read in until now.
UPDATE messages m
SET seq = ordered.position
FROM (SELECT id, row_number() OVER (PARTITION BY conversation_id ORDER BY created_at, id) AS position
      FROM messages) ordered
WHERE m.id = ordered.id;

UPDATE conversations c
SET last_message_seq = latest.seq
FROM (SELECT conversation_id, max(seq) AS seq FROM messages GROUP BY conversation_id) latest
WHERE c.id = latest.conversation_id;

ALTER TABLE messages ALTER COLUMN seq SET NOT NULL;
ALTER TABLE messages ADD CONSTRAINT messages_conversation_seq_key UNIQUE (conversation_id, seq);

-- Runs as the inserting role, under RLS: a message can only be appended to a conversation that
-- role sees, and that is the conversation whose counter it moves.
CREATE FUNCTION assign_message_seq() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE conversations
    SET last_message_seq = last_message_seq + 1
    WHERE id = NEW.conversation_id
    RETURNING last_message_seq INTO NEW.seq;
    IF NEW.seq IS NULL THEN
        RAISE EXCEPTION 'no visible conversation % for message %', NEW.conversation_id, NEW.id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER messages_assign_seq
    BEFORE INSERT ON messages
    FOR EACH ROW EXECUTE FUNCTION assign_message_seq();

-- 2. Audit outbox: the existing rows in their current drain order, then an identity after them.
ALTER TABLE audit_outbox ADD COLUMN seq BIGINT;

UPDATE audit_outbox a
SET seq = ordered.position
FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS position FROM audit_outbox) ordered
WHERE a.id = ordered.id;

ALTER TABLE audit_outbox ALTER COLUMN seq SET NOT NULL;
ALTER TABLE audit_outbox ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY;
SELECT setval(pg_get_serial_sequence('audit_outbox', 'seq'), coalesce((SELECT max(seq) FROM audit_outbox), 0) + 1,
              false);

DROP INDEX idx_audit_outbox_tenant_status_created;
CREATE INDEX idx_audit_outbox_tenant_status_seq ON audit_outbox (tenant_id, drain_status, seq);
