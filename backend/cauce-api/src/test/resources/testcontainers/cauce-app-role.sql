-- Testcontainers init script: runs once as the container superuser, before Flyway.
--
-- Creates the least-privilege role the application-under-test connects as, so the cauce-api
-- integration tests exercise Row-Level Security through the real application path. The DML
-- grants are applied by Flyway migration V10 (which runs as the owner); this script only
-- adds LOGIN and the test password wired in AbstractApiIntegrationTest.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cauce_app') THEN
        CREATE ROLE cauce_app LOGIN PASSWORD 'cauce_app_test'
            NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;
