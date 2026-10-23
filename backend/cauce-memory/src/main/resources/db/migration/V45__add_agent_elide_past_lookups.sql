-- V45: a per-agent switch for an experiment (deferred R1). When on, the results of lookups made in
-- earlier turns (the profile, the menu, availability) are left out of the model's context: each
-- call stays, with a marker for its result, and the agent looks it up again when it needs it.
-- Off by default, so every agent keeps the whole history until the eval harness has measured it.
ALTER TABLE agents ADD COLUMN elide_past_lookups BOOLEAN NOT NULL DEFAULT false;
