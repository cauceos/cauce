-- V42: the person's own words on a handoff (ADR 0007, B7).
--
-- summary is the agent's account of the case; customer_message is what the person wrote, quoted
-- verbatim from one of their messages in the conversation (checked when the case is opened), so
-- the owner's notice can show it apart and labelled instead of mixed into the agent's text.
-- Optional: NULL when the agent quoted nothing, which is every handoff before this migration.
-- Personal content like the summary: here, where deletion operates, never in the audit chain.
ALTER TABLE handoffs ADD COLUMN customer_message VARCHAR(1000);
