-- V27: drop what the V26 re-key replaced (ADR 0004, PC-2 and PC-4: V25 create, V26 re-key,
-- V27 drop).
--
-- A conversation is now (agent_id, identity_id, channel_config_id). The opaque reference lives
-- on the identity (identities.value), the channel on the identity and on the config, and no
-- reader of conversations.channel_type is left. The V3 index over (channel_type,
-- external_identity_ref) goes with its columns; idx_conversations_identity_id (V26) serves
-- "an interlocutor's threads" instead. V13's arbiter was already replaced in V26.
--
-- Not reversible by data: the dropped values are exactly what V26 copied into identities, so
-- a rollback would recompute them from the join rather than restore them.

DROP INDEX idx_conversations_identity;

ALTER TABLE conversations
    DROP COLUMN channel_type,
    DROP COLUMN external_identity_ref;
