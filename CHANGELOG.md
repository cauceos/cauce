# Changelog

Changes a user or contributor of a local stack must act on. The full history is `git log`.

## Unreleased

### Breaking: a message is a list of parts

A message is now stored as an ordered list of typed parts (ADR 0004, decision 2): migration
V54 moves every row's `content` and `tool_content` into a new `parts` column and drops the two.
The backfill is mechanical; nothing is lost and nothing needs doing to a local database beyond
starting the new version.

**The REST representation of a message changes shape** (`GET /v1/conversations/{id}/messages`):

- New: `parts`, an array of objects with a `type` — `{"type": "text", "text": "..."}`,
  `{"type": "tool_call", "tool_call_id", "tool_name", "input"}` or
  `{"type": "tool_result", "tool_call_id", "tool_name", "output", "is_error"}`.
- Gone: `tool_content`. Its fields are the tool part's, under the same names.
- Unchanged: `content`, now derived from the parts (the text, or a tool message's name / output).

**The input does not change**: `POST /v1/agents/{agentId}/messages` still takes `content`,
stored as one text part.

**The audit hash of a message is now v2.** New `conduct.message.received` and
`conduct.agent.responded` entries carry `content_hash` computed over the parts and say
`"content_hash_version": 2`; entries already written keep their v1 hash. Both are defined in
`docs/spec/audit-chain-format.md` §6.3. The chain format itself does not change.

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
