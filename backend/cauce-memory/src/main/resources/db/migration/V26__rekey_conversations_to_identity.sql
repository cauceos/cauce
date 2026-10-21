-- V26: re-key conversations to the interlocutor identity (ADR 0004, decision 1; PC-1, PC-4).
--
-- A conversation now references who it is with (identity_id -> identities, V25) and the door
-- that person came in through (channel_config_id -> channel_configs, V16). The config is not
-- the person: one identity may reach the same agent through two bots, and those are two
-- threads. channel_config_id is NULL for the built-in api channel, which has no config;
-- "NOT NULL when an adapter ingested, NULL for api" is an invariant of ConversationService,
-- deliberately not a CHECK (the channel list is SPI-owned and the database does not know it).
--
-- The live-thread arbiter moves from (agent_id, channel_type, external_identity_ref) (V13) to
-- (agent_id, identity_id, channel_config_id) WHERE status = 'OPEN', declared NULLS NOT
-- DISTINCT so that api threads, whose config is NULL, still deduplicate: without it every
-- NULL is distinct and two concurrent first api messages would split the thread. It is the
-- ON CONFLICT arbiter of the insert-first resolve-or-create, as V13 was.
--
-- channel_type and external_identity_ref stay, still NOT NULL and still written, until V27
-- drops them together with the V3 identity index (PC-4: V25 create, V26 re-key, V27 drop).
--
-- Backfill is mechanical (ADR 0004): only telegram and api conversations can exist, so each
-- row's kind follows from its channel_type. Any other channel_type aborts the migration
-- instead of guessing a kind. Backfilled identities are owned by the agent's tenant, and their
-- ids are UUIDv7 built from the earliest conversation start, so they sort with the rest.
-- channel_config_id is backfilled only where the agent has exactly one config for the
-- conversation's channel; otherwise it stays NULL and outbound delivery for that legacy thread
-- is skipped with a WARN, the behaviour it already had for an ambiguous origin.
--
-- Runs as the owner role (Flyway), which bypasses RLS, so the backfill sees every tenant.

ALTER TABLE conversations
    ADD COLUMN identity_id       UUID REFERENCES identities (id) ON DELETE RESTRICT,
    ADD COLUMN channel_config_id UUID REFERENCES channel_configs (id) ON DELETE RESTRICT;

DO $$
DECLARE
    unknown_channel text;
BEGIN
    SELECT channel_type INTO unknown_channel FROM conversations
     WHERE channel_type NOT IN ('telegram', 'api') LIMIT 1;
    IF unknown_channel IS NOT NULL THEN
        RAISE EXCEPTION 'V26 backfill: conversation on channel % has no identity kind mapping',
            unknown_channel;
    END IF;
END;
$$;

-- One identity per (tenant, channel_type, kind, value). The UUIDv7 is the standard
-- construction: a random v4 with the first 48 bits overlaid by the Unix time in milliseconds
-- and the version nibble set from 4 to 7.
INSERT INTO identities (id, tenant_id, channel_type, kind, value, created_at)
SELECT encode(set_bit(set_bit(overlay(uuid_send(gen_random_uuid())
                  placing substring(int8send(floor(extract(epoch FROM src.first_seen) * 1000)::bigint)
                                    FROM 3)
                  FROM 1 FOR 6), 52, 1), 53, 1), 'hex')::uuid,
       src.tenant_id, src.channel_type, src.kind, src.value, src.first_seen
  FROM (SELECT a.tenant_id,
               c.channel_type,
               CASE c.channel_type WHEN 'telegram' THEN 'PROVIDER_USER_ID'
                                   WHEN 'api'      THEN 'CLIENT_REFERENCE' END AS kind,
               c.external_identity_ref AS value,
               min(c.started_at) AS first_seen
          FROM conversations c
          JOIN agents a ON a.id = c.agent_id
         GROUP BY a.tenant_id, c.channel_type, c.external_identity_ref) src
ON CONFLICT ON CONSTRAINT identities_key DO NOTHING;

UPDATE conversations c
   SET identity_id = i.id
  FROM agents a, identities i
 WHERE a.id = c.agent_id
   AND i.tenant_id = a.tenant_id
   AND i.channel_type = c.channel_type
   AND i.kind = CASE c.channel_type WHEN 'telegram' THEN 'PROVIDER_USER_ID'
                                    WHEN 'api'      THEN 'CLIENT_REFERENCE' END
   AND i.value = c.external_identity_ref;

UPDATE conversations c
   SET channel_config_id = cfg.id
  FROM (SELECT agent_id, channel_type, min(id::text)::uuid AS id
          FROM channel_configs
         GROUP BY agent_id, channel_type
        HAVING count(*) = 1) cfg
 WHERE c.channel_type <> 'api'
   AND cfg.agent_id = c.agent_id
   AND cfg.channel_type = c.channel_type;

ALTER TABLE conversations ALTER COLUMN identity_id SET NOT NULL;

DROP INDEX idx_conversations_one_open_per_identity;

CREATE UNIQUE INDEX idx_conversations_one_open_thread
    ON conversations (agent_id, identity_id, channel_config_id) NULLS NOT DISTINCT
    WHERE status = 'OPEN';

-- Lookups and FK checks by identity (an interlocutor's threads) and by config (a config's
-- threads, and the RESTRICT check when a config row is deleted).
CREATE INDEX idx_conversations_identity_id ON conversations (identity_id);
CREATE INDEX idx_conversations_channel_config_id ON conversations (channel_config_id);
