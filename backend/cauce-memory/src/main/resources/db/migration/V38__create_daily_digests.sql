-- V38: the nightly summary to the owner (ADR 0007, B10).
--
-- business_profiles.daily_digest_time: the local time the summary is sent at; NULL means no
-- summary. daily_digest_skip_empty: a day with nothing to report is recorded but not sent.
--
-- daily_digests: one row per business and local date, composed by code from the records (never by
-- a model): tomorrow's confirmed bookings, the open handoffs, and the unanswered questions recorded
-- in [covered_from, covered_to). The UNIQUE (tenant_id, local_date) is the arbiter of an
-- insert-first claim, so a re-run, a second instance or a manual run never sends a second summary
-- for the same day. The text itself goes to the owner through the owner-notification outbox (V37),
-- unless the day is empty and the business skips empty days (notified = false),
-- with its reply-window rule. content holds names and phones: business content, never audited in
-- clear. Hierarchical RLS keyed on tenant_id.
--
-- daily_digest_candidates (SECURITY DEFINER, ADR 0001): the scheduler's narrow cross-tenant read --
-- the businesses that want a summary, with their time zone and time; it then works under each
-- tenant's RLS.

ALTER TABLE business_profiles ADD COLUMN daily_digest_time TIME;
ALTER TABLE business_profiles ADD COLUMN daily_digest_skip_empty BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE daily_digests (
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    local_date     DATE        NOT NULL,
    covered_from   TIMESTAMPTZ NOT NULL,
    covered_to     TIMESTAMPTZ NOT NULL,
    booking_count  INTEGER     NOT NULL,
    handoff_count  INTEGER     NOT NULL,
    question_count INTEGER     NOT NULL,
    content        TEXT        NOT NULL,
    notified       BOOLEAN     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT daily_digests_day_key UNIQUE (tenant_id, local_date)
);

CREATE INDEX idx_daily_digests_tenant ON daily_digests (tenant_id, id);

CREATE FUNCTION daily_digest_is_visible(d_tenant_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public
AS $$ SELECT owner_record_is_visible(d_tenant_id); $$;

ALTER TABLE daily_digests ENABLE ROW LEVEL SECURITY;
CREATE POLICY hierarchical_visibility ON daily_digests
    FOR ALL USING (daily_digest_is_visible(tenant_id)) WITH CHECK (daily_digest_is_visible(tenant_id));

CREATE FUNCTION daily_digest_candidates()
    RETURNS TABLE (tenant_id uuid, time_zone varchar, daily_digest_time time)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT p.tenant_id, p.time_zone, p.daily_digest_time
    FROM business_profiles p
    WHERE p.daily_digest_time IS NOT NULL;
$$;

REVOKE EXECUTE ON FUNCTION daily_digest_candidates() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION daily_digest_candidates() TO cauce_app;
