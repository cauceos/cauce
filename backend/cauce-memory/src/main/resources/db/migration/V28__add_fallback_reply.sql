-- V28: the fallback reply of ADR 0005, C3.
--
-- When an invocation fails for good, the interlocutor receives a fallback text instead of
-- silence, and never internal detail. It is persisted as a message of its own role, FALLBACK:
-- delivered like a reply and readable over the API, but never part of the model's context.
-- failure_type records which terminal failure it answers, in the internal taxonomy (the same
-- names pending_invocations.failure_type holds, V17); the API maps it to the public reason.
-- It is present exactly for FALLBACK rows, which carry no tool content.
--
-- agents.fallback_reply is the per-agent text; NULL means the platform default
-- (cauce.orchestration.fallback.default-reply). A fallback is a short apology, so it is
-- bounded.

ALTER TABLE messages DROP CONSTRAINT messages_role_check;
ALTER TABLE messages ADD CONSTRAINT messages_role_check
    CHECK (role IN ('USER', 'AGENT', 'SYSTEM', 'TOOL_CALL', 'TOOL_RESULT', 'FALLBACK'));

ALTER TABLE messages DROP CONSTRAINT messages_tool_content_shape;
ALTER TABLE messages ADD CONSTRAINT messages_tool_content_shape CHECK (
    (role IN ('USER', 'AGENT', 'SYSTEM', 'FALLBACK') AND tool_content IS NULL)
    OR (role IN ('TOOL_CALL', 'TOOL_RESULT') AND tool_content IS NOT NULL)
);

ALTER TABLE messages ADD COLUMN failure_type VARCHAR(50);
ALTER TABLE messages ADD CONSTRAINT messages_failure_type_shape
    CHECK ((role = 'FALLBACK') = (failure_type IS NOT NULL));

ALTER TABLE agents ADD COLUMN fallback_reply TEXT;
ALTER TABLE agents ADD CONSTRAINT agents_fallback_reply_shape
    CHECK (fallback_reply IS NULL OR char_length(btrim(fallback_reply)) BETWEEN 1 AND 1000);
