# Quickstart

From a clean clone to a responding agent, and to an audit chain you verify yourself, with
**no local JDK**. Everything runs in Docker: PostgreSQL, Redis and the Cauce API, plus a local
LLM served by [Ollama](https://ollama.com) if you take the no-cost path.

The demo is deliberately an *agent*, not a chatbot: you ask it for the time, the model
calls the built-in `get_current_time` tool, and answers from the tool result — the full
agentic loop (LLM → tool call → tool result → final answer). Every step of it is written to a
signed, hash-chained audit log, which the last step checks with an independent verifier.

## Pick a path

| Path | You need | First run |
|---|---|---|
| **A. Anthropic key** | an Anthropic API key (usage is billed by Anthropic: cents for the demo) | **under 10 minutes** |
| **B. Your own Ollama** | [Ollama](https://ollama.com/download) installed, `ollama pull qwen2.5:3b` done | **under 10 minutes** |
| **C. Ollama in Docker** | nothing else: no key, no cost, nothing leaves your machine | **≈5 GB to download** (Ollama image + model): the time is your connection's |

Measured cold (empty Docker, fresh clone, nothing cached) on a laptop with 8 cores and 8 GB
for Docker, clone → agent answer: **A ≈3 minutes, B ≈3.5 minutes** — most of it the app image
build (Gradle inside Docker) and the smaller images. **C took between 8 and 17 minutes** on the
same machine in three runs: the ≈5 GB download is all the difference, so on a slow connection
budget 30–40 minutes. Afterwards every path starts in under a minute: images, the model and
the database are kept in Docker volumes.

## Requirements

- **Docker** with **Docker Compose 2.20 or later** (Docker Desktop on Windows/macOS, or
  Docker Engine with the compose plugin on Linux). `docker compose version` shows yours.
- **RAM for Docker**: ~2 GB for A and B; **~8 GB for C** (the 3B model holds ≈3.7 GB once
  loaded; measured steady state ≈4.5 GB).
- **Disk**: ~3 GB for A and B; **~12 GB for C** (the Ollama image is ≈5 GB unpacked, the
  model 1.9 GB), plus a few GB of Docker build cache.
- **curl** (preinstalled on macOS/Linux/Windows 10+; the PowerShell scripts do not need it).
- **Node 18 or later**, only for the last step (the independent audit verifier).
- No GPU required for C: inference runs on CPU, so answers take seconds to tens of seconds.
  With a GPU-enabled Docker (Linux + NVIDIA toolkit) it is much faster; on Apple Silicon,
  path B uses the GPU and C does not.

## 1. Start the stack

```bash
git clone https://github.com/cauceos/cauce.git
cd cauce
```

**A. Anthropic key.** Put the key in `.env` (gitignored; compose reads it from there and
passes it to the app):

```bash
echo "ANTHROPIC_API_KEY=<your key>" >> .env
echo "QUICKSTART_PROVIDER=anthropic" >> .env
docker compose --profile api up -d --build
```

**B. Your own Ollama.** The app reaches it from its container through `host.docker.internal`:

```bash
ollama pull qwen2.5:3b
echo "OLLAMA_BASE_URL=http://host.docker.internal:11434/v1" >> .env
docker compose --profile api up -d --build
```

On Linux, Ollama listens on `127.0.0.1` only by default, which a container cannot reach: start
it with `OLLAMA_HOST=0.0.0.0` (for the systemd service: `systemctl edit ollama`, then
`Environment="OLLAMA_HOST=0.0.0.0"`). On Windows and macOS nothing changes.

Cauce sizes every request to the context length your Ollama serves, 4096 tokens unless you
started it with another `OLLAMA_CONTEXT_LENGTH`: Ollama cannot be asked for more per request. If
you raised it, put the same value in `.env` (`OLLAMA_CONTEXT_LENGTH=16384`).

**C. Ollama in Docker.**

```bash
docker compose --profile quickstart up -d --build
```

`up` returns once the images are downloaded and the app image is built; that is the long part
of the first run. With C, the model (1.9 GB) is then pulled in the background:
`docker compose logs -f ollama-init` shows its progress, and the next step waits for it.

## 2. Talk to the agent

```bash
./scripts/quickstart.sh        # macOS / Linux / Git Bash
```

```powershell
# Windows (works under the default Restricted execution policy; changes no machine state)
powershell -ExecutionPolicy Bypass -File scripts\quickstart.ps1
```

It waits for the API, checks that your provider is configured on it, wires up a demo agent and
asks it the time. Expected ending (the wording is the model's; small local models often answer
with the raw timestamp):

```
== Agent reply:
   It is currently 2026-10-23T20:09:30Z.
   (The agent called the get_current_time tool 1 time(s) to answer — that is the agentic loop.)
```

It then prints a ready-made `curl` to keep talking to the agent.

## 3. Verify the audit chain yourself

Every action of the demo — the tenants and the agent created, the API key issued, the message
received, the tool called and its result, the reply — was recorded in a per-tenant audit chain:
each entry is hashed, linked to the previous one, and signed with an Ed25519 key the quickstart
generated on the first start. Check it:

```bash
./scripts/verify-audit.sh      # Windows: powershell -ExecutionPolicy Bypass -File scripts\verify-audit.ps1
```

It checks the demo client's chain twice, with two programs that share no code:

1. **The instance** verifies it (`GET /v1/tenants/{id}/audit/chain-verification`).
2. The chain is dumped from the database, and the instance's published key registry copied
   out, into `.quickstart/` (gitignored).
3. **The independent verifier** (`tools/audit-chain-verifier`, one file, Node's standard
   library only) recomputes every hash and checks every signature against that registry.

Expected (ids and hashes are yours):

```
== 1. The instance verifies the chain of tenant 01a16a8d-…...
   status: VALID · entries verified: 7 · signatures: 7 signed and verified, 0 unsigned, 0 unverifiable
…
== 3. The independent verifier (tools/audit-chain-verifier, no Cauce code) recomputes it...
   status: VALID
   entries verified: 7
   pre-chain entries: 0 (written before chaining existed; not checked, not a defect)
   head: #7 5d8437289be715f2…
   signatures: 7 verified · 0 unsigned · 0 unverifiable
```

The dump may hold a few entries more than the instance counted in step 1: entries reach the
chain asynchronously, a moment after the action, and asking for a verification is itself
recorded in the chain it verified.

What a VALID establishes — and what it does not (an actor holding the signing key, a chain cut
back to an earlier state) — is in the verifier's guide:
**[tools/audit-chain-verifier/README.md](tools/audit-chain-verifier/README.md)**.

The signing key is generated inside the app container and kept in this stack's `audit-keys`
volume: it never touches the repository, and `docker compose down -v` deletes it together with
the database it signed. That suits a local demo. A real deployment generates its key with
`./gradlew :cauce-governance:generateAuditSigningKey`, keeps it out of the database's reach and
publishes the registry (see the verifier's guide and the audit chain specification).

## 4. Optional: the demo bar

The hospitality sector pack (`examples/hosteleria`) loads a whole reference business — a bar
with its sheet, menu and agenda, a waiter agent for customers and an agent for the owner:

```bash
./scripts/demo-bar.sh          # Windows: powershell -ExecutionPolicy Bypass -File scripts\demo-bar.ps1
```

Its agents use Anthropic (`claude-sonnet-5-5`) by default, so it needs path A's key; the script
checks the instance has the provider before creating anything. With path B or C,
`MODEL_PROVIDER=ollama MODEL_NAME=qwen2.5:3b ./scripts/demo-bar.sh` works, with a noticeably
weaker waiter; give it room with `OLLAMA_CONTEXT_LENGTH=16384` in `.env` (and, with your own
Ollama, the same on the server), since the bar's prompts outgrow Ollama's default 4096 tokens. It ends with a ready-made `curl` to talk to the waiter, and optionally connects
two Telegram bots: see [examples/hosteleria/README.md](examples/hosteleria/README.md).

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
is gone and the key with it. Recovery is a clean restart — wipe this stack's volumes **and**
the cached state file, then start over (use the profile you started with):

```bash
docker compose --profile quickstart down -v
rm -f .quickstart.env
docker compose --profile quickstart up -d
```

(The scripts also detect a stale cached key against a reset database and start fresh
on their own.)

`down -v` deletes only this clone's volumes: containers and volumes are named after the
clone's directory (`cauce-postgres-1`, `cauce_postgres-data`), so another clone on the same
machine is unaffected. Two clones in directories of the same name: set `COMPOSE_PROJECT_NAME`
in one's `.env`.

## What the script does (the same calls, by hand)

All `/v1/**` endpoints take `Authorization: Bearer <api-key>`; JSON is snake_case.
Agents live under a CLIENT tenant, so the chain is operator → partner → client → agent:

```bash
KEY=<bootstrap key>   OP=<operator uuid from the same log line>
BASE=http://localhost:8080

# 0. The providers an agent may name, each with "configured": true/false on this instance
#    (an agent on an unconfigured one cannot answer)
curl -s $BASE/v1/llm-providers -H "Authorization: Bearer $KEY"

# 1. Tenant chain
curl -s -X POST $BASE/v1/tenants/partner -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" -d "{\"name\":\"Demo Partner\",\"operator_id\":\"$OP\"}"
curl -s -X POST $BASE/v1/tenants/client -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" -d '{"name":"Demo Client","partner_id":"<partner id>"}'

# 2. Agent (path C shown; path A: "model_provider": "anthropic", "model_name": "claude-haiku-5-5")
curl -s -X POST $BASE/v1/tenants/<client id>/agents -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" -d '{
       "name": "Demo Agent",
       "system_prompt": "You have a get_current_time tool; use it for time questions.",
       "model_provider": "ollama",
       "model_name": "qwen2.5:3b",
       "tools": ["get_current_time"]
     }'

# 3. Message (returns 202 with conversation_id and invocation_id — processing is async)
curl -s -X POST $BASE/v1/agents/<agent id>/messages -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" \
     -d '{"external_identity_ref":"me","content":"What time is it right now?"}'

# 4. Poll status, then read the reply
curl -s $BASE/v1/invocations/<invocation id> -H "Authorization: Bearer $KEY"
curl -s $BASE/v1/conversations/<conversation id>/messages -H "Authorization: Bearer $KEY"

# 5. The instance's verdict on the client's audit chain
curl -s $BASE/v1/tenants/<client id>/audit/chain-verification -H "Authorization: Bearer $KEY"
```

The messages list shows the whole loop: your `USER` message, the model's `TOOL_CALL`,
the `TOOL_RESULT`, and the final `AGENT` answer.

## Configuration knobs

Copy `.env.example` to `.env` to override defaults:

- `QUICKSTART_PROVIDER` (default `ollama`; or `anthropic`) — the provider the scripts give the
  demo agent. `ANTHROPIC_API_KEY` and `ANTHROPIC_MODEL` (default `claude-haiku-5-5`) go with
  `anthropic`. Compose also takes `ANTHROPIC_API_KEY` from your shell's environment.
- `OLLAMA_MODEL` (default `qwen2.5:3b`) — the model pulled and used by the demo agent.
  Small models are honest about their size: tool calling works, but expect occasional
  misses. `qwen2.5:7b` (~4.7 GB, needs ~8 GB RAM for the model alone) is noticeably
  more reliable if your machine can take it.
- `OLLAMA_BASE_URL` — your own Ollama instead of the containerized one (path B).
- `OLLAMA_CONTEXT_LENGTH` (default `4096`, Ollama's own) — the context length Ollama serves. Compose
  gives it to the containerized Ollama and to the app, which sizes its requests to it; with your
  own Ollama, it must equal what that server is started with. 16384 takes ≈0.5 GB more RAM.
- `API_PORT` (default `8080`), `OLLAMA_PORT` (default `11434`) — host ports, next to the
  existing `POSTGRES_PORT=5433`, `REDIS_PORT=6379`, `ADMINER_PORT=8081`.
- `COMPOSE_PROJECT_NAME` — only to tell apart two clones in directories of the same name.

## Troubleshooting

- **`docker compose` rejects the file (`required` / profiles)** — your Compose is older than
  2.20: update Docker Desktop, or the compose plugin.
- **`docker compose ps` shows `cauce-api` unhealthy / restarting** — check
  `docker compose logs cauce-api`. First boot must run all Flyway migrations; postgres
  must be healthy first (compose ordering handles this).
- **"This instance has no 'anthropic' provider configured"** — the key did not reach the app.
  It must be in `.env` (or your shell) when compose creates the container: add it, then
  `docker compose --profile api up -d` recreates the app with it.
- **`failure_reason: AGENT_MISCONFIGURED`** — the agent names a provider the instance has not
  configured (or a model its provider rejects). `docker compose logs cauce-api` says which
  setting is missing, e.g. `LLM provider 'anthropic' is not configured on this instance:
  ANTHROPIC_API_KEY is not set`.
- **The first answer times out (`failure_reason: PROVIDER_UNAVAILABLE` or `TIMEOUT`)** —
  CPU model loading can be slow on modest hardware. The script warms the model first;
  if you go manual, run one
  `curl localhost:11434/api/generate -d '{"model":"qwen2.5:3b","prompt":"hi","stream":false}'`
  before the first message. The app-side per-request timeout is set to 180 s in compose.
- **Path B: the model is never found** — the script checks `localhost:11434` on the host: is
  `ollama serve` running, and was `ollama pull qwen2.5:3b` done? On Linux, see the
  `OLLAMA_HOST` note in path B.
- **Port already in use** — set `API_PORT` / `OLLAMA_PORT` in `.env`.
- **Model pull seems stuck** — `docker compose logs -f ollama-init` shows pull progress.
- **Lost the bootstrap key** — see [Your API key](#your-api-key-read-this); clean restart
  including `rm -f .quickstart.env`.
- **`verify-audit` reports signatures unverifiable** — the app was not started by this compose
  file (e.g. `bootRun`), so there is no registry in its container to copy.
- **Regular development** (compose infra + `./gradlew :cauce-api:bootRun` on the host) is
  unchanged: plain `docker compose up -d` starts only PostgreSQL/Redis/Adminer. Don't run
  the quickstart and bootRun at once unless you change `API_PORT`.

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
