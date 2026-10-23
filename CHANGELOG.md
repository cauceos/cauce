# Changelog

Changes a user or contributor of a local stack must act on. The full history is `git log`.

## Unreleased

### Breaking: Docker Compose names are per clone

`docker-compose.yml` no longer fixes the project name, the container names, the network name
or the volume names. The project takes the name of the clone's directory (Compose's default),
so containers are `<dir>-<service>-1` and volumes `<dir>_<volume>`: two clones on one machine
no longer share a database, and `docker compose down -v` in one no longer wipes the other's.

The volumes were renamed too: `cauce_pgdata` → `postgres-data`, `cauce_redisdata` →
`redis-data`, `cauce_ollamadata` → `ollama-data`, `cauce_observedata` → `observe-data` (each
prefixed by the project name). The old volumes are not deleted, but a stack started from the new
file does not see them: it starts on an empty database.

**Upgrading an existing local stack.**

1. Stop the old containers, which still hold the ports. Before pulling the change,
   `docker compose --profile quickstart --profile observability down` (without `-v`); after
   pulling it, remove them by their old names:
   `docker rm -f cauce-postgres cauce-redis cauce-adminer cauce-ollama cauce-ollama-init cauce-api cauce-openobserve`
   (a name that does not exist is reported and skipped).
2. To **keep the old database**, declare the old volume as external in
   `docker-compose.override.yml` (gitignored; see `docker-compose.override.yml.example`):

   ```yaml
   volumes:
     postgres-data:
       name: cauce_pgdata
       external: true
   ```

   The same works for `ollama-data` (`cauce_ollamadata`, saves the model download) and
   `observe-data` (`cauce_observedata`). Without the override the stack starts fresh: the
   Postgres init scripts run again and the first start logs a new operator key.
3. Once nothing uses an old volume any more, delete it: `docker volume rm cauce_pgdata`.
