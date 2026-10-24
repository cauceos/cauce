-- V54: a message is an ordered list of parts (ADR 0004, decision 2; unit 2a of its amendment of
-- 2026-10-24).
--
-- `parts` becomes the row's only content, in the one JSON form the application defines
-- (MessageParts): a list of objects, each with its `type`. The backfill is mechanical and loses
-- nothing:
--
--   a text role (USER, AGENT, SYSTEM, FALLBACK)  -> [{"type": "text", "text": content}]
--   TOOL_CALL                                     -> [{"type": "tool_call"} || tool_content]
--   TOOL_RESULT                                   -> [{"type": "tool_result"} || tool_content]
--
-- A tool message's `content` was only a rendering of its tool content (the tool name, or the
-- output with "[empty result]" for a blank one), which the application derives again; the tool
-- keys are V14's, so a tool part is its tool_content plus the type. Then `content`, `tool_content`
-- and the CHECK that tied the two together (V14, V28) go: one source of truth, not a pair.
--
-- The audit chain does not change: entries already written keep their content_hash v1, computed
-- over the text, and are never recomputed; new ones carry v2, computed over the parts.

ALTER TABLE messages ADD COLUMN parts JSONB;

UPDATE messages SET parts = CASE
    WHEN role = 'TOOL_CALL' THEN jsonb_build_array(jsonb_build_object('type', 'tool_call') || tool_content)
    WHEN role = 'TOOL_RESULT' THEN jsonb_build_array(jsonb_build_object('type', 'tool_result') || tool_content)
    ELSE jsonb_build_array(jsonb_build_object('type', 'text', 'text', content))
END;

ALTER TABLE messages ALTER COLUMN parts SET NOT NULL;
ALTER TABLE messages ADD CONSTRAINT messages_parts_shape
    CHECK (jsonb_typeof(parts) = 'array' AND jsonb_array_length(parts) >= 1);

ALTER TABLE messages DROP CONSTRAINT messages_tool_content_shape;
ALTER TABLE messages DROP COLUMN tool_content;
ALTER TABLE messages DROP COLUMN content;
