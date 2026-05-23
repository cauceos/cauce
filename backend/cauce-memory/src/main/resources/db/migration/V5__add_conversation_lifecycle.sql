-- V5: conversation lifecycle timestamps for the ESCALATED and ARCHIVED transitions.
-- closed_at already exists (V3); escalated_at and archived_at are added here. All three
-- are nullable and set by the domain when the corresponding transition occurs.

ALTER TABLE conversations ADD COLUMN escalated_at TIMESTAMPTZ;
ALTER TABLE conversations ADD COLUMN archived_at  TIMESTAMPTZ;

-- Consistency invariants: a status implies its timestamp is present. The domain already
-- guarantees these; the CHECKs are defence in depth against any code path that bypasses
-- the domain (consistent with the existing status CHECK). Every reachable state
-- satisfies them, including ARCHIVED reached from CLOSED or ESCALATED (each implication
-- only constrains its own status).
ALTER TABLE conversations
    ADD CONSTRAINT conversations_closed_at_present
        CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL),
    ADD CONSTRAINT conversations_escalated_at_present
        CHECK (status <> 'ESCALATED' OR escalated_at IS NOT NULL),
    ADD CONSTRAINT conversations_archived_at_present
        CHECK (status <> 'ARCHIVED' OR archived_at IS NOT NULL);
