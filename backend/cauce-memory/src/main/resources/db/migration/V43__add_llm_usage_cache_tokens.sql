-- V43: how much of each call's prompt a provider served from its prompt cache or wrote to it.
--
-- input_tokens stays the whole prompt; cache_read_tokens and cache_write_tokens are the parts of it
-- the provider read from or wrote to its cache (0 for a provider that does not cache, and for every
-- call before this migration). Facts, like the other token counts: what they cost is derived from a
-- price table, never stored here.
ALTER TABLE llm_usage_records
    ADD COLUMN cache_read_tokens  INTEGER NOT NULL DEFAULT 0 CHECK (cache_read_tokens >= 0),
    ADD COLUMN cache_write_tokens INTEGER NOT NULL DEFAULT 0 CHECK (cache_write_tokens >= 0);
