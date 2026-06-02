-- V10: enforce RLS at runtime by granting DML to the least-privilege cauce_app role.
--
-- Resolves the TODO carried in V1-V9: at runtime the application connects as cauce_app
-- (NOSUPERUSER, NOBYPASSRLS, not a table owner) so the hierarchical_visibility policies
-- actually filter every query. Flyway and the operator bootstrap keep using a privileged
-- owner connection, which bypasses RLS because every table is ENABLE (not FORCE) ROW
-- LEVEL SECURITY.
--
-- The role is created here WITHOUT a password and NOLOGIN, so no credentials live in
-- version control while the grants below can be applied in every environment. The LOGIN
-- attribute and a password are set out of band, only where the app actually connects as
-- cauce_app:
--   * local dev  -> docker/postgres/init/02-cauce-app-role.sql
--   * CI / tests -> Testcontainers withInitScript (cauce-api integration tests)
-- This migration runs as the table owner (the Flyway/admin connection).

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cauce_app') THEN
        CREATE ROLE cauce_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;

-- Schema access and row-level DML on the current tables. cauce_app gets no DDL and no
-- ownership, so it can read/write rows (subject to RLS) but cannot alter or bypass the
-- schema.
GRANT USAGE ON SCHEMA public TO cauce_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cauce_app;

-- Future tables created by the owner inherit the same DML grant automatically, so new
-- entity migrations no longer need an explicit grant to cauce_app.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cauce_app;

-- Flyway's bookkeeping table is owner-only; cauce_app never touches it. Revoke the DML
-- the blanket grant above would otherwise hand out, keeping the runtime role minimal.
REVOKE ALL ON flyway_schema_history FROM cauce_app;
