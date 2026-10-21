-- Testcontainers init script: runs once as the container superuser, before Flyway.
--
-- Creates the least-privilege role the application-under-test connects as, so the
-- cauce-business integration tests exercise Row-Level Security through the real
-- application path. DML grants are applied by Flyway migration V10; this script only adds LOGIN
-- and the test password wired in AbstractBusinessIntegrationTest.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cauce_app') THEN
        CREATE ROLE cauce_app LOGIN PASSWORD 'cauce_app_test'
            NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;
