-- V64: a case's contact is a phone or an e-mail, with its type (ADR 0014, amendment of 2026-11-10).
-- contact_phone becomes contact, long enough for an e-mail; every contact stored so far is a phone.
-- The type is a closed domain value, so it gets a CHECK; contact and its type are both there or neither.

ALTER TABLE handoffs RENAME COLUMN contact_phone TO contact;
ALTER TABLE handoffs ALTER COLUMN contact TYPE VARCHAR(254);
ALTER TABLE handoffs ADD COLUMN contact_type VARCHAR(10) CHECK (contact_type IN ('PHONE', 'EMAIL'));
UPDATE handoffs SET contact_type = 'PHONE' WHERE contact IS NOT NULL;
ALTER TABLE handoffs ADD CONSTRAINT handoffs_contact_shape CHECK ((contact IS NULL) = (contact_type IS NULL));
