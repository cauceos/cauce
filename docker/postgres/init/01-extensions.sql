-- Runs once, on first initialization of an empty PostgreSQL data volume
-- (files in /docker-entrypoint-initdb.d/ are executed against POSTGRES_DB).
--
-- Enables pgvector so vector columns are available from the start.
CREATE EXTENSION IF NOT EXISTS vector;
