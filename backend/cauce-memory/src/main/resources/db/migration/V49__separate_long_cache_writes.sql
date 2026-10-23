-- V49: cache writes for the long retention apart from the rest (ADR 0010, amendment of 2026-10-23).
--
-- A provider may keep a cached prefix for a short time (5 minutes on Anthropic) or a long one (an
-- hour), and the long write costs more (2 x input against 1.25 x). cache_write_tokens stays every
-- write; cache_write_long_tokens is the part of it written for the long retention (0 for every call
-- before this migration, and for a provider with one retention only).
--
-- llm_prices gains the price of a long-retention write. Absent, it is priced as input, the same
-- rule as the other cache prices: a price row that omits it under-prices long writes, so the
-- operator states it for a provider that has them.
--
-- The cost view prices short writes and long writes each at their own price; everything else is
-- V47's view.

ALTER TABLE llm_usage_records
    ADD COLUMN cache_write_long_tokens INTEGER NOT NULL DEFAULT 0
        CHECK (cache_write_long_tokens >= 0 AND cache_write_long_tokens <= cache_write_tokens);

ALTER TABLE llm_prices
    ADD COLUMN cache_write_long_usd_per_mtok NUMERIC(12,6) CHECK (cache_write_long_usd_per_mtok >= 0);

DROP VIEW llm_usage_costs;

CREATE VIEW llm_usage_costs WITH (security_invoker = true) AS
WITH prices AS (
    SELECT lp.id,
           lp.provider,
           lp.model,
           lp.input_usd_per_mtok,
           lp.output_usd_per_mtok,
           lp.cache_read_usd_per_mtok,
           lp.cache_write_usd_per_mtok,
           lp.cache_write_long_usd_per_mtok,
           lp.valid_from,
           lead(lp.valid_from) OVER (PARTITION BY lp.provider, lp.model ORDER BY lp.valid_from, lp.id) AS valid_to
    FROM llm_prices lp
)
SELECT u.id,
       u.tenant_id,
       u.agent_id,
       u.conversation_id,
       u.invocation_id,
       u.provider,
       u.model,
       u.input_tokens,
       u.output_tokens,
       u.cache_read_tokens,
       u.cache_write_tokens,
       u.cache_write_long_tokens,
       u.created_at,
       p.id AS price_id,
       ((u.input_tokens - u.cache_read_tokens - u.cache_write_tokens) * p.input_usd_per_mtok
            + u.cache_read_tokens * coalesce(p.cache_read_usd_per_mtok, p.input_usd_per_mtok)
            + (u.cache_write_tokens - u.cache_write_long_tokens)
                  * coalesce(p.cache_write_usd_per_mtok, p.input_usd_per_mtok)
            + u.cache_write_long_tokens * coalesce(p.cache_write_long_usd_per_mtok, p.input_usd_per_mtok)
            + u.output_tokens * p.output_usd_per_mtok) / 1000000 AS cost_usd
FROM llm_usage_records u
LEFT JOIN prices p
       ON p.provider = u.provider
      AND p.model = u.model
      AND p.valid_from <= u.created_at
      AND (p.valid_to IS NULL OR u.created_at < p.valid_to);
