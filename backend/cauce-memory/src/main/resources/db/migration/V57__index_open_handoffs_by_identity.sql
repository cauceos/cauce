-- V57: list_my_cases reads a person's OPEN handoffs in a business (ADR 0013, T1), a lookup by
-- (tenant_id, identity_id, status) that idx_handoffs_tenant and idx_handoffs_open do not serve.
-- Ordered by id, the UUIDv7 that follows creation.

CREATE INDEX idx_handoffs_identity ON handoffs (tenant_id, identity_id, status, id);
