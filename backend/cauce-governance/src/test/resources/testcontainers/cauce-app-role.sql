-- Testcontainers init script: runs once as the container superuser, before Flyway.
--
-- Creates the least-privilege role the application-under-test connects as, so the
-- cauce-governance integration tests exercise Row-Level Security AND the append-only grant
-- through the real application path (the audit_log_entries UPDATE/DELETE revoke of V21 only
-- means something when the tests run as cauce_app). DML grants are applied by Flyway
-- migration V10; this script only adds LOGIN and the test password wired in
-- AbstractGovernanceIntegrationTest.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cauce_app') THEN
        CREATE ROLE cauce_app LOGIN PASSWORD 'cauce_app_test'
            NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;
