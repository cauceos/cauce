-- V40: the time a person asked for when booking (ADR 0007, B6).
--
-- Capacity stays per shift: requested_time is what the person said ("a las 21:30"), kept so it
-- is read back on confirmation and listed in the owner's summary. The domain keeps it within the
-- booking's shift (from its start, before its end); NULL means only the shift was named, which is
-- what every booking before this migration was.
ALTER TABLE bookings ADD COLUMN requested_time TIME;
