# Quickstart

From a clean clone to a responding agent, with **no paid API keys and no local JDK**.
Everything runs in Docker: PostgreSQL, Redis, the Cauce API, and a local LLM served
by [Ollama](https://ollama.com).

The demo is deliberately an *agent*, not a chatbot: you ask it for the time, the model
calls the built-in `get_current_time` tool, and answers from the tool result — the full
agentic loop (LLM → tool call → tool result → final answer) running locally.

## Requirements

- **Docker** with Docker Compose (Docker Desktop on Windows/macOS, or Docker Engine on Linux).
- **~8 GB of RAM available to Docker.** Measured steady state is ≈4.5 GB (the 3B model
  holds ≈3.7 GB once loaded, the JVM ≈0.5 GB, infra the rest); 8 GB leaves headroom.
- **~15 GB of free disk.** The Ollama image alone is 8 GB on disk (it bundles GPU
  libraries), the model is 1.9 GB, the app image 0.4 GB, infra images ≈0.9 GB, plus a
  few GB of Docker build cache.
- **curl** (preinstalled on macOS/Linux/Windows 10+; the PowerShell script does not need it).
- No GPU required. Inference runs on CPU: expect answers in seconds to tens of seconds,
  not milliseconds. With a GPU-enabled Docker (Linux + NVIDIA toolkit) it is much faster.
- Internet is only needed for the initial downloads (≈6 GB total, the Ollama image and
  the model being the bulk). After that, everything is local — no data leaves your machine.

Honest timing. Measured cold (nothing cached) on a 16-core laptop with fast internet;
your bandwidth and CPU move these numbers:

| Phase | Measured | On modest hardware/bandwidth |
|---|---|---|
| App image build (Gradle inside Docker) | 1.5 min | 5–10 min |
| Base images + model downloads (≈6 GB) | ~3 min | bandwidth-bound: 10–30 min |
| First boot (DB migrations + bootstrap) | ~35 s | < 2 min |
| First agent answer (CPU inference, warmed) | ~10 s | up to ~1 min |

Total measured: **~5 minutes** clone → answer. On a slower connection budget
**15–30 minutes** — the ≈6 GB of downloads dominate. Subsequent runs start in under a
minute: images and the model are cached in Docker volumes.

## Run it

```bash
git clone https://github.com/cauceos/cauce.git
cd cauce
docker compose --profile quickstart up -d --build
```

Then, while it builds and pulls, run the helper script — it waits for everything,
wires up a demo agent, and asks it the time:

```bash
./scripts/quickstart.sh        # macOS / Linux / Git Bash
```

```powershell
# Windows (works under the default Restricted execution policy; changes no machine state)
powershell -ExecutionPolicy Bypass -File scripts\quickstart.ps1
```

Expected ending:

```
== Agent reply:
   The current time is 14:32 UTC on July 22, 2026.
   (The agent called the get_current_time tool 1 time(s) to answer — that is the agentic loop.)
```

The script prints a ready-made `curl` at the end so you can keep talking to the agent.

## Your API key (read this)

On the very first boot against an empty database, Cauce creates the root **operator**
tenant and logs its API key **exactly once** (`WARN` in the app logs). It cannot be
recovered afterwards — only its hash is stored.

The script copies it from the logs into **`.quickstart.env`** (gitignored) for you.
To grab it manually instead:

```bash
docker compose logs cauce-api | grep "cannot be recovered"
```

If the `cauce-api` container is ever recreated before you saved the key, the log line
is gone and the key with it. Recovery is a clean restart — wipe the volumes **and** the
cached state file, then start over:

```bash
docker compose --profile quickstart down -v
rm -f .quickstart.env
docker compose --profile quickstart up -d
```

(The scripts also detect a stale cached key against a reset database and start fresh
on their own.)

## What the script does (the same calls, by hand)

All `/v1/**` endpoints take `Authorization: Bearer <api-key>`; JSON is snake_case.
Agents live under a CLIENT tenant, so the chain is operator → partner → client → agent:

```bash
KEY=<bootstrap key>   OP=<operator uuid from the same log line>
BASE=http://localhost:8080

# 1. Tenant chain
curl -s -X POST $BASE/v1/tenants/partner -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" -d "{\"name\":\"Demo Partner\",\"operator_id\":\"$OP\"}"
curl -s -X POST $BASE/v1/tenants/client -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" -d '{"name":"Demo Client","partner_id":"<partner id>"}'

# 2. Agent (Ollama is keyless and enabled by default in dev)
curl -s -X POST $BASE/v1/tenants/<client id>/agents -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" -d '{
       "name": "Demo Agent",
       "system_prompt": "You have a get_current_time tool; use it for time questions.",
       "model_provider": "ollama",
       "model_name": "qwen2.5:3b"
     }'

# 3. Message (returns 202 with conversation_id and invocation_id — processing is async)
curl -s -X POST $BASE/v1/agents/<agent id>/messages -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" \
     -d '{"external_identity_ref":"me","content":"What time is it right now?"}'

# 4. Poll status, then read the reply
curl -s $BASE/v1/invocations/<invocation id> -H "Authorization: Bearer $KEY"
curl -s $BASE/v1/conversations/<conversation id>/messages -H "Authorization: Bearer $KEY"
```

The messages list shows the whole loop: your `USER` message, the model's `TOOL_CALL`,
the `TOOL_RESULT`, and the final `AGENT` answer.

## Configuration knobs

Copy `.env.example` to `.env` to override defaults:

- `OLLAMA_MODEL` (default `qwen2.5:3b`) — the model pulled and used by the demo agent.
  Small models are honest about their size: tool calling works, but expect occasional
  misses. `qwen2.5:7b` (~4.7 GB, needs ~8 GB RAM for the model alone) is noticeably
  more reliable if your machine can take it.
- `API_PORT` (default `8080`), `OLLAMA_PORT` (default `11434`) — host ports, next to the
  existing `POSTGRES_PORT=5433`, `REDIS_PORT=6379`, `ADMINER_PORT=8081`.
- Native Ollama on the host (e.g. Apple Silicon GPU): see the commented block in
  `docker-compose.override.yml.example`.

## Troubleshooting

- **`docker compose ps` shows `cauce-api` unhealthy / restarting** — check
  `docker compose logs cauce-api`. First boot must run all Flyway migrations; postgres
  must be healthy first (compose ordering handles this).
- **The first answer times out (`failure_reason: PROVIDER_UNAVAILABLE` or `TIMEOUT`)** —
  CPU model loading can be slow on modest hardware. The script warms the model first;
  if you go manual, run one
  `curl localhost:11434/api/generate -d '{"model":"qwen2.5:3b","prompt":"hi","stream":false}'`
  before the first message. The app-side per-request timeout is set to 180 s in compose.
- **Port already in use** — set `API_PORT` / `OLLAMA_PORT` in `.env`.
- **Model pull seems stuck** — `docker compose logs -f ollama-init` shows pull progress.
- **Lost the bootstrap key** — see [Your API key](#your-api-key-read-this); clean restart
  including `rm -f .quickstart.env`.
- **Regular development** (compose infra + `./gradlew :cauce-api:bootRun` on the host) is
  unchanged: plain `docker compose up -d` starts only PostgreSQL/Redis/Adminer. Don't run
  the quickstart profile and bootRun at once unless you change `API_PORT`.

## Optional: connect a Telegram bot

The quickstart is fully local. If you want to talk to your agent from Telegram, you need
two extra things: a bot token from [@BotFather](https://t.me/BotFather) and a public URL
(Telegram must reach your machine — use a tunnel such as `cloudflared` or `ngrok`).

```bash
# 1. Bind the channel to your agent (the webhook_secret is returned exactly once)
curl -s -X POST $BASE/v1/agents/<agent id>/channels -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" \
     -d '{"channel_type":"telegram","credential":"<bot token from BotFather>"}'
# → note "id" (the config id) and "webhook_secret" from the response

# 2. Expose port 8080 publicly, e.g.:  cloudflared tunnel --url http://localhost:8080

# 3. Point Telegram at the webhook, authenticated by the secret
curl -s "https://api.telegram.org/bot<bot token>/setWebhook" \
     -d "url=https://<your tunnel host>/webhooks/channels/<config id>" \
     -d "secret_token=<webhook_secret>"
```

Message your bot: the update arrives at `/webhooks/channels/{config id}`, is verified
against the secret, ingested, answered by the agent, and the reply is delivered back to
the Telegram chat.
