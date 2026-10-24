-- V55: per-agent reply to a message its channel delivered but Cauce does not read (a voice note, a
-- photo; ADR 0006, W5), the same mechanism as the fallback reply (V28). NULL means the channel's
-- platform default (cauce.channels.<channel>.unsupported-message-reply); a set text has the
-- fallback reply's shape.

ALTER TABLE agents ADD COLUMN unsupported_message_reply TEXT;
ALTER TABLE agents ADD CONSTRAINT agents_unsupported_message_reply_shape
    CHECK (unsupported_message_reply IS NULL
           OR char_length(btrim(unsupported_message_reply)) BETWEEN 1 AND 1000);
