-- V58: a new thread after inactivity (ADR 0013, T3). The next inbound message closes the OPEN
-- thread and starts another when the thread has gone this many hours without a conversational
-- message (USER, AGENT or FALLBACK). 24 by default, so every existing agent has it on; 0 never closes.
ALTER TABLE agents ADD COLUMN new_thread_after_hours INTEGER NOT NULL DEFAULT 24
    CONSTRAINT agents_new_thread_after_hours_range CHECK (new_thread_after_hours BETWEEN 0 AND 8760);
