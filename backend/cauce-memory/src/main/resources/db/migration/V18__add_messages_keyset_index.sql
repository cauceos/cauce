-- Keyset-pagination hot path for the conversation-messages endpoint:
--   WHERE conversation_id = ? AND id > ? ORDER BY id LIMIT n
-- Conversations are append-heavy and can grow unboundedly, so the page must come straight
-- off an index instead of a per-page sort over the whole thread. agents/tenants pagination
-- deliberately gets no equivalent index: per-parent cardinality is tens-to-hundreds, the
-- existing single-column indexes plus a top-N sort are negligible there, and the index can
-- be added later as a pure additive migration if that ever changes.

CREATE INDEX idx_messages_conversation_id_keyset ON messages (conversation_id, id);
