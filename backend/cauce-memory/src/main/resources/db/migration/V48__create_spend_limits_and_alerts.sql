-- V48: the monthly spend limit of a client and the alerts it raises (ADR 0010, S5, S6).
--
-- tenant_spend_limits: at most one limit per tenant, in US$ per calendar month in UTC. Only CLIENT
-- tenants carry one; the service checks the tier, since a CHECK cannot read another table. Only a
-- strict ancestor sets, changes or removes it: the client reads its limit but cannot lift it. This
-- departs from ADR 0002 (a tenant manages itself) and is enforced here as well as in the service:
-- reading needs the row's tenant visible, writing needs it visible AND different from the current
-- tenant. updated_at is stamped by the application from the database clock (ADR 0008).
--
-- spend_alerts: one row per tenant, month and threshold reached (80 or 100 %), with the limit and
-- the spend that crossed it. The unique constraint makes a threshold report once a month: the
-- check inserts with ON CONFLICT DO NOTHING and only the insert that creates the row notifies the
-- owner and records the audit event. period is the first day of the month in UTC.
--
-- owner_notifications.kind gains the two notices of the limit.
--
-- No grant needed: cauce_app inherits DML via the V10 default privileges.

CREATE TABLE tenant_spend_limits (
    id                UUID PRIMARY KEY,
    tenant_id         UUID          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    monthly_limit_usd NUMERIC(12,2) NOT NULL CHECK (monthly_limit_usd > 0),
    set_by_tenant_id  UUID          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    updated_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT tenant_spend_limits_tenant_key UNIQUE (tenant_id)
);

CREATE TABLE spend_alerts (
    id                UUID PRIMARY KEY,
    tenant_id         UUID          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    period            DATE          NOT NULL CHECK (extract(day FROM period) = 1),
    threshold_percent SMALLINT      NOT NULL CHECK (threshold_percent IN (80, 100)),
    monthly_limit_usd NUMERIC(12,2) NOT NULL,
    spent_usd         NUMERIC(18,6) NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT spend_alerts_once UNIQUE (tenant_id, period, threshold_percent)
);

CREATE INDEX idx_spend_alerts_tenant ON spend_alerts (tenant_id, id);

CREATE FUNCTION spend_record_is_visible(r_tenant_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    t_id uuid;
    t_parent uuid;
BEGIN
    SELECT id, parent_tenant_id INTO t_id, t_parent FROM tenants WHERE id = r_tenant_id;
    IF t_id IS NULL THEN
        RETURN false;
    END IF;
    RETURN tenant_is_visible(t_id, t_parent);
END;
$$;

-- Managed: visible, and not the current tenant's own row.
CREATE FUNCTION spend_limit_is_managed(l_tenant_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public
AS $$ SELECT spend_record_is_visible(l_tenant_id) AND l_tenant_id IS DISTINCT FROM current_tenant_id(); $$;

ALTER TABLE tenant_spend_limits ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON tenant_spend_limits
    FOR SELECT
    USING (spend_record_is_visible(tenant_id));

CREATE POLICY set_by_ancestor_insert ON tenant_spend_limits
    FOR INSERT
    WITH CHECK (spend_limit_is_managed(tenant_id));

CREATE POLICY set_by_ancestor_update ON tenant_spend_limits
    FOR UPDATE
    USING (spend_limit_is_managed(tenant_id))
    WITH CHECK (spend_limit_is_managed(tenant_id));

CREATE POLICY set_by_ancestor_delete ON tenant_spend_limits
    FOR DELETE
    USING (spend_limit_is_managed(tenant_id));

ALTER TABLE spend_alerts ENABLE ROW LEVEL SECURITY;

CREATE POLICY hierarchical_visibility ON spend_alerts
    FOR ALL
    USING (spend_record_is_visible(tenant_id))
    WITH CHECK (spend_record_is_visible(tenant_id));

ALTER TABLE owner_notifications DROP CONSTRAINT owner_notifications_kind_check;
ALTER TABLE owner_notifications ADD CONSTRAINT owner_notifications_kind_check
    CHECK (kind IN ('HANDOFF', 'DAILY_DIGEST', 'SPEND_WARNING', 'SPEND_LIMIT_REACHED'));
