-- V56: transcription is usage, priced and counted against the spend limit (ADR 0012, V4).
--
-- A voice note's transcription is a provider call like a model's, billed by the length of the audio
-- instead of by tokens. It goes into the same ledger, priced by the same versioned table and costed by
-- the same view, so the usage endpoint and the spend limit read it with no second path:
--
--   llm_usage_records.audio_seconds    the seconds of audio a call transcribed (0 for a model call)
--   llm_prices.audio_usd_per_minute    the price of a minute of audio; NULL: the row does not price it
--
-- A call that transcribed audio, priced by a row without an audio price, has a null cost: an unpriced
-- call, like one with no price at all (V51's rule), refused before it happens to a client with a limit
-- (S7). A local transcriber that costs nothing is priced 0, not left unpriced: the limit then says it.

ALTER TABLE llm_usage_records ADD COLUMN audio_seconds INTEGER NOT NULL DEFAULT 0 CHECK (audio_seconds >= 0);

ALTER TABLE llm_prices ADD COLUMN audio_usd_per_minute NUMERIC(12,6) CHECK (audio_usd_per_minute >= 0);

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
           lp.audio_usd_per_minute,
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
           WHEN u.audio_seconds > 0 AND p.audio_usd_per_minute IS NULL THEN NULL
           ELSE ((u.input_tokens - u.cache_read_tokens - u.cache_write_tokens) * p.input_usd_per_mtok
                     + u.cache_read_tokens * coalesce(p.cache_read_usd_per_mtok, p.input_usd_per_mtok)
                     + (u.cache_write_tokens - u.cache_write_long_tokens)
                           * coalesce(p.cache_write_usd_per_mtok, p.input_usd_per_mtok)
                     + u.cache_write_long_tokens * coalesce(p.cache_write_long_usd_per_mtok, 0) -- 0 when there are none
                     + u.output_tokens * p.output_usd_per_mtok) / 1000000
                + u.audio_seconds * coalesce(p.audio_usd_per_minute, 0) / 60 -- 0 when there are none
       END AS cost_usd,
       u.audio_seconds
FROM llm_usage_records u
LEFT JOIN prices p
       ON p.provider = u.provider
      AND p.model = u.model
      AND p.valid_from <= u.created_at
      AND (p.valid_to IS NULL OR u.created_at < p.valid_to);
