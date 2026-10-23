# Observability runbook

How an operator watches a Cauce instance that answers bars' customers unattended: what to set up
once, what each alert means, and how to follow one message. The decisions are
[ADR 0011](../adr/0011-observability-for-unattended-operation.md).

**Status: verified end to end on 2026-10-23, locally** (alerts to Telegram, a message's trace, the
dashboard; ADR 0011, Verification). Not yet on a server: §4 (Healthchecks) is paused and §5 (the
nightly chain verification) is not scheduled anywhere until there is one.

What leaves the application is ids, counts and closed vocabularies: never a message's text, an
identity's value, a prompt or a provider's error detail (ADR 0011, L2, T3). The tools below never
see what a customer said.

## 1. The backend: OpenObserve

One container on the API's server: traces, metrics, logs and alerts, about 300–500 MB of RAM.

```bash
# .env: OPENOBSERVE_ROOT_EMAIL and OPENOBSERVE_ROOT_PASSWORD (a real secret), see .env.example
docker compose --profile observability up -d
```

OpenObserve refuses to start with a weak password: 8 to 128 characters, with an upper-case and a
lower-case letter, a digit and a symbol (the container exits; `docker compose logs openobserve`
says so). The first user is created on the first start and kept in the `observe-data` volume:
changing the password in `.env` afterwards changes nothing until the volume is recreated, or the
password is changed in the UI.

The UI is on `http://<server>:5080`. Do not publish that port to the internet: reach it through an
SSH tunnel (`ssh -L 5080:localhost:5080 <server>`) or a VPN. The application talks to it on the
server's private network.

## 2. The application exports over OTLP

Nothing is exported until these are set in the application's environment (an unset endpoint
installs no exporter; never set them to an empty value). `<auth>` is
`base64("<root email>:<root password>")` — better, an OpenObserve service account's.

```bash
MANAGEMENT_OTLP_TRACING_ENDPOINT=http://<openobserve>:5080/api/default/v1/traces
MANAGEMENT_OTLP_TRACING_HEADERS_AUTHORIZATION="Basic <auth>"
MANAGEMENT_OTLP_LOGGING_ENDPOINT=http://<openobserve>:5080/api/default/v1/logs
MANAGEMENT_OTLP_LOGGING_HEADERS_AUTHORIZATION="Basic <auth>"
MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true
MANAGEMENT_OTLP_METRICS_EXPORT_URL=http://<openobserve>:5080/api/default/v1/metrics
MANAGEMENT_OTLP_METRICS_EXPORT_HEADERS_AUTHORIZATION="Basic <auth>"
```

Every message is traced (`management.tracing.sampling.probability=1.0`); the scheduled jobs are
not, so a trace is a message. Metrics are pushed every minute as deltas (each point is what happened
in its minute), so a window's count is `sum_over_time(...)`, never `increase(...)`: a counter that
appears already counting — a new agent, a restart — is seen whole. Log lines go out at the level
the console has: keep `dev.cauce` at `INFO` in production (the `prod` profile does), since the
failure detail is logged at `DEBUG`.

What arrives:

| Signal | Name in OpenObserve | Tags |
|---|---|---|
| Messages in, by agent | `cauce_orchestration_invocations_requested` | `tenant_id`, `agent_id` |
| Replies, by agent | `cauce_orchestration_invocations_completed` | `tenant_id`, `agent_id` |
| Fallback replies | `cauce_orchestration_fallbacks` | `tenant_id`, `agent_id`, `failure_type` |
| Failures | `cauce_orchestration_invocations_failed` | `failure_type` |
| Turn latency | `cauce_orchestration_turn_latency` | `tenant_id`, `agent_id` |
| Model calls / answers / failures | `cauce_orchestration_llm_calls` / `_llm_responses` / `_llm_failures` | `provider`, `model` (`finish_reason`; `error_type`, the exception's type) |
| Tokens | `cauce_orchestration_llm_tokens` | `provider`, `model`, `kind` |
| Gaps between a person's messages | `cauce_conversation_customer_messages` | `since_previous`, `tenant_id`, `agent_id` |
| Queue | `cauce_queue_pending`, `cauce_queue_oldest_due_age` (s) | — |
| Audit outbox | `cauce_audit_outbox_pending`, `cauce_audit_outbox_oldest_age` (s) | — |
| Owner notices not sent (24 h) | `cauce_owner_notices_unsent` | — |
| Clients past 80 % / at 100 % of their limit | `cauce_spend_alerts` | `threshold` |
| Spend this month (US$) | `cauce_spend_month` | `tenant_id` |

Tags never include a conversation, a message or an identity (ADR 0011, M1). With two agents per
bar this is fine for tens of bars; review it from a few hundred.

## 3. Alerts to the operator's Telegram

The alerts go to a Telegram bot of the operator's own — never one of a bar's. Its token lives in
OpenObserve's destination, never in Cauce.

1. In Telegram, talk to `@BotFather`: `/newbot`, keep the token.
2. Send any message to the new bot from the account (or a group with the bot) that must receive
   the alerts, then read the chat id from
   `https://api.telegram.org/bot<TOKEN>/getUpdates` (`message.chat.id`).
3. Once the application has been exporting for a minute or two, provision the rules:

```bash
OPENOBSERVE_URL=http://localhost:5080 \
OPENOBSERVE_ROOT_EMAIL=... OPENOBSERVE_ROOT_PASSWORD=... \
TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... \
scripts/observability/provision.sh
```

It also imports the operator's dashboard, *Cauce — operación* (`scripts/observability/dashboard.json`):
messages, fallback share and turn latency per agent, failed calls per provider, the queue, the
audit outbox, each client's spend this month, unsent owner notices, tokens per model and failures
per type. The script is idempotent: re-run it to change the bot or after editing a rule. It needs
`curl` and `python3`.

| Alert | Fires when | First look |
|---|---|---|
| `cauce-provider-down` | over half the calls to a provider failed in 5 min (≥ 3 calls) | the provider's status page; the failed invocations' `last_error` (`GET /v1/invocations/{id}`) |
| `cauce-fallback-rate` | an agent answered over 20 % of its messages with the fallback in 15 min (≥ 5) | the `failure_type` of `cauce_orchestration_fallbacks` for that agent; its traces |
| `cauce-queue-stuck` | the oldest due invocation has waited over 2 min | the worker's logs; is the application up, is the database reachable |
| `cauce-audit-chain-behind` | audit events have waited over 5 min to be chained | ERROR logs of `AuditOutboxDrainer` in the logs stream |
| `cauce-spend-limit-reached` | a client's agents are refused for its spend limit | `cauce_spend_month` for that `tenant_id`; raise or keep the limit |

The rules are evaluated every minute, and each series alerts on its own: a provider, an agent, a
client. Once a series fires it stays silent for a while (30 min to 12 h, by rule), so a long outage
is one message, not one a minute — and one bar's alert never silences another's. Each message
carries the alert's name, one line per series that fired with its ids and a readable value
("Proveedor anthropic: 100 % de las llamadas fallaron", "… : 100 % de respaldo"), and the time in
UTC. Find a tenant by id through the API, never in the alert. OpenObserve attaches every rule to
the `cauce_queue_pending` stream, which exists once the application has exported once: provision
after that. Re-provisioning re-creates the rules, so a series still in its window fires again.

After changing a rule, run `scripts/observability/test-alerts.sh`: against a throwaway OpenObserve,
it checks that two agents get one alert each, none repeated, readable (about two minutes, Docker).
To check the whole path on a development PC, with your own bot, run
`scripts/verificar-alertas.ps1` (Windows PowerShell; it explains its two steps).

## 4. A server that is down: Healthchecks

An application that is down cannot send its own alert, and neither can an OpenObserve on the same
server. A cron on the server pings [Healthchecks](https://healthchecks.io) while the API answers;
when the pings stop, Healthchecks notifies (Telegram is one of its integrations).

**Status: configured, paused.** There is no Cauce server yet, and a development PC is not on 24
hours a day: an active check would page every night. The check exists in Healthchecks, paused;
when the server exists, install the cron below on it and resume the check. Until then, step 2
is not done anywhere.

1. Create a check with a period of 1 minute and a grace of 3; copy its ping URL.
2. On the server, `crontab -e`:

```cron
* * * * * curl -fsS -m 10 http://localhost:8080/actuator/health | grep -q '"UP"' && curl -fsS -m 10 --retry 3 https://hc-ping.com/<uuid> > /dev/null
```

`/actuator/health` answers `{"status":"UP"}` and nothing else: no components, no versions
(`management.endpoint.health.show-details=never`; only the `dev` profile shows them).
`/actuator/metrics` needs an API key.

## 5. The audit chain: full verification, outside Cauce

The application alerts on what precedes a broken chain (the drain falling behind, errors writing
it). The full verification is done by the independent verifier,
[`tools/audit-chain-verifier`](../../tools/audit-chain-verifier/README.md), against a dump, on a
schedule of the operator's — never by Cauce itself (CLAUDE.md, design rules). For example, nightly:

```cron
30 3 * * * /opt/cauce/verify-audit-chain.sh && curl -fsS -m 10 https://hc-ping.com/<uuid-of-a-second-check> > /dev/null || curl -fsS -m 10 https://hc-ping.com/<uuid-of-a-second-check>/fail > /dev/null
```

where `verify-audit-chain.sh`, for each tenant, produces the dump and the key registry with the
SQL of the verifier's README ("Producing a dump") and runs `node verify.mjs <dump.json>
<registry.json>`, exiting non-zero unless every chain is `VALID` (exit codes: `0` VALID, `1`
BROKEN, `2` UNVERIFIABLE, `3` could not run). Run it against a copy (a restored backup or a
replica), not the live database, and keep the printed heads: a head recorded outside the database
is what tells a shortened chain apart. A second Healthchecks check, pinged on success, notifies
when a night passes without a verified chain.

## 6. Following one message

From any id — the `invocation_id` the API returned, a `conversation_id`, an alert's agent:

1. **Logs**: in OpenObserve, the logs stream, filter by `invocationid` (or `conversationid`,
   `agentid`, `tenantid`; OpenObserve lower-cases the fields). Each line carries `trace_id`.
2. **Trace**: open the trace by its id. One message is one trace: the HTTP request or webhook that
   brought it in, `cauce.invocation` (the worker, possibly later), one `cauce.llm.call` per model
   round (provider, model, tokens, finish reason), one `cauce.tool.call` per tool (name, outcome)
   and `cauce.reply.delivery` (channel type, outcome). A failure shows its exception type, never its
   message.
3. **The record**: what was said and the provider's error detail are in Cauce, behind the API and
   its tenancy (`GET /v1/conversations/{id}/messages`, `GET /v1/invocations/{id}`), erasable with
   the conversation — not in any of the tools above.

## 7. Checking it end to end

After setting it up, once:

1. `curl -s http://<server>:8080/actuator/health` answers `{"status":"UP"}` only.
2. Send a message to a test agent; in OpenObserve, its trace shows the spans above under one
   trace id, and the logs stream has lines with its `invocationId`.
3. In the metrics, `cauce_queue_pending` and `cauce_orchestration_invocations_requested` have
   points.
4. Point a test agent at a model the provider does not serve (or stop its key) and send three
   messages: `cauce-provider-down` reaches the Telegram chat within a few minutes, and
   `cauce-fallback-rate` after five.
5. Pause the Healthchecks cron for a few minutes: Healthchecks notifies.
