-- V50: how long the provider is asked to keep an agent's prompt prefix cached (ADR 0010, S9).
-- SHORT, the default, keeps every agent as it was (minutes); LONG asks for about an hour, so a
-- person who answers after a few minutes still reads the conversation from the cache. A neutral
-- value: each LLM adapter maps it to what its provider offers.
ALTER TABLE agents ADD COLUMN prompt_cache_retention VARCHAR(10) NOT NULL DEFAULT 'SHORT'
    CHECK (prompt_cache_retention IN ('SHORT', 'LONG'));
