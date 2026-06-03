-- V13: at most one OPEN conversation per (agent, channel, external identity).
--
-- resolveOrStartConversation routes an inbound message to the live thread for a given
-- (agent, channel, external user), creating one only if none is OPEN. Without a DB-level
-- invariant, two concurrent first messages (at-least-once channel webhooks) can both miss
-- the OPEN row and both insert, splitting the conversation. This partial unique index makes
-- that impossible: at most one OPEN row per tuple. It is also the arbiter index for the
-- INSERT ... ON CONFLICT ... DO NOTHING used by resolve-or-create.
--
-- Partial (status = 'OPEN') on purpose: a user may accumulate many CLOSED/ESCALATED/ARCHIVED
-- threads with the same agent on the same channel over time; only the live one must be unique.
-- The existing finder findByAgentIdAndChannelTypeAndExternalIdentityRefAndStatus(...OPEN)
-- already returns Optional, i.e. the application already assumes this invariant.
--
-- No grant needed (an index inherits table privileges). Created non-concurrently: Flyway runs
-- each migration in a transaction and the table is early-stage / near-empty.

CREATE UNIQUE INDEX idx_conversations_one_open_per_identity
    ON conversations (agent_id, channel_type, external_identity_ref)
    WHERE status = 'OPEN';
