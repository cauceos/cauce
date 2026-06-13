-- V14: tool-call / tool-result message support (foundation for the tool loop).
--
-- Additive: extends the role enum with TOOL_CALL/TOOL_RESULT and adds a nullable jsonb column
-- carrying the structured tool payload — {tool_call_id, tool_name, input} for a call and
-- {tool_call_id, tool_name, output, is_error} for a result. The tool_call_id correlates a
-- result back to its originating call (every LLM provider requires this correlation id).
--
-- The text `content` column is unchanged (NOT NULL): tool messages also carry a human-readable
-- rendering (the tool name for a call, the output for a result) so the conversation reads
-- naturally; the machine-readable source of truth is tool_content. A guard CHECK keeps the two
-- in sync: the tool payload is present exactly for the tool roles.
--
-- The flat message sequence (one row per message, role-discriminated) is unchanged; assembling
-- the provider-specific nested format is the LLM adapter's concern, not the schema's.

ALTER TABLE messages DROP CONSTRAINT messages_role_check;
ALTER TABLE messages ADD CONSTRAINT messages_role_check
    CHECK (role IN ('USER', 'AGENT', 'SYSTEM', 'TOOL_CALL', 'TOOL_RESULT'));

ALTER TABLE messages ADD COLUMN tool_content JSONB;

ALTER TABLE messages ADD CONSTRAINT messages_tool_content_shape CHECK (
    (role IN ('USER', 'AGENT', 'SYSTEM') AND tool_content IS NULL)
    OR (role IN ('TOOL_CALL', 'TOOL_RESULT') AND tool_content IS NOT NULL)
);
