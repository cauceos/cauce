-- V7: per-agent LLM call configuration -- temperature and max response tokens used when
-- building invocations. Both columns are nullable so existing (pre-V7) agent rows are
-- preserved; the domain coalesces a null value to its default (0.7 / 4096) when reading.
-- The CHECK constraints bound the values when present (NULL is allowed to mean "unset").

ALTER TABLE agents ADD COLUMN temperature         DOUBLE PRECISION;
ALTER TABLE agents ADD COLUMN max_response_tokens INTEGER;

ALTER TABLE agents
    ADD CONSTRAINT agents_temperature_range
        CHECK (temperature IS NULL OR (temperature >= 0.0 AND temperature <= 1.0)),
    ADD CONSTRAINT agents_max_response_tokens_positive
        CHECK (max_response_tokens IS NULL OR max_response_tokens > 0);
