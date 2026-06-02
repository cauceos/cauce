-- Runs once, on first initialization of an empty PostgreSQL data volume (files in
-- /docker-entrypoint-initdb.d/ run as POSTGRES_USER against POSTGRES_DB). To re-run after
-- editing, recreate the volume: `docker compose down -v && docker compose up -d`.
--
-- Creates the least-privilege role the application connects as in local dev, so Row-Level
-- Security is actually enforced (the POSTGRES_USER 'cauce' is a superuser and bypasses
-- RLS). The DML grants are applied by Flyway migration V10, which runs as the owner; this
-- script only adds LOGIN and a dev-only password matching application-dev.properties.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cauce_app') THEN
        CREATE ROLE cauce_app LOGIN PASSWORD 'cauce_app_dev'
            NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;
