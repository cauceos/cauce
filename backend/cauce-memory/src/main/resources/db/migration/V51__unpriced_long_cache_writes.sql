-- V51: a long-retention write with no price is an unpriced call, not an input token (ADR 0010, S8).
--
-- V49 priced a long write as input when the price row had no cache_write_long_usd_per_mtok, the rule
-- of the other cache prices. A long write costs 2 x input on Anthropic, so that rule under-states the
-- spend, and a spend limit would let it through cheaper. Now a call that wrote to the long-retention
-- cache, priced by a row without that price, has a null cost: it counts as an unpriced call, like a
-- call with no price at all, and a client with a spend limit is refused it before it happens (S7).
-- The short cache prices keep their rule: absent, priced as input.

CREATE OR REPLACE VIEW llm_usage_costs WITH (security_invoker = true) AS
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
       CASE
           WHEN u.cache_write_long_tokens > 0 AND p.cache_write_long_usd_per_mtok IS NULL THEN NULL
           ELSE ((u.input_tokens - u.cache_read_tokens - u.cache_write_tokens) * p.input_usd_per_mtok
                     + u.cache_read_tokens * coalesce(p.cache_read_usd_per_mtok, p.input_usd_per_mtok)
                     + (u.cache_write_tokens - u.cache_write_long_tokens)
                           * coalesce(p.cache_write_usd_per_mtok, p.input_usd_per_mtok)
                     + u.cache_write_long_tokens * coalesce(p.cache_write_long_usd_per_mtok, 0) -- 0 when there are none
                     + u.output_tokens * p.output_usd_per_mtok) / 1000000
       END AS cost_usd
FROM llm_usage_records u
LEFT JOIN prices p
       ON p.provider = u.provider
      AND p.model = u.model
      AND p.valid_from <= u.created_at
      AND (p.valid_to IS NULL OR u.created_at < p.valid_to);
