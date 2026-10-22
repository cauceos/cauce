-- V41: a booking's code stays the same through its changes (ADR 0007, B6).
--
-- A change is still a new row that replaces the confirmed one (V36), but it now carries the code
-- of the booking it changes: the code a person was given names their booking, whatever they
-- change. So several rows of one booking share a code -- the original, its proposed changes, the
-- confirmed change -- and (tenant_id, code) is no longer unique. What stays unique is the code of
-- each booking's first row (replaces_booking_id IS NULL): a code names one booking per business.
-- Rows written before this migration keep the codes they had.
ALTER TABLE bookings DROP CONSTRAINT bookings_code_key;
CREATE UNIQUE INDEX bookings_code_key ON bookings (tenant_id, code) WHERE replaces_booking_id IS NULL;
-- Every operation a person makes by code reads all the rows of that booking.
CREATE INDEX idx_bookings_code ON bookings (tenant_id, code);
