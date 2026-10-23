# ADR 0011: Observability for unattended operation

Status: Accepted.

## Context

Cauce is meant to answer the customers of real bars without anyone watching. When a provider goes
down, a client reaches its spend limit, the invocation queue stops draining or the audit chain
stops being written, the operator has to learn it from a message on the phone, and then find out
what happened to one conversation from its ids alone.

What existed: Spring Boot Actuator (`health` and `info` public, `metrics` behind an API key), a
request id in the logging context of HTTP requests only, JSON logs (ECS) in production,
in-process Micrometer meters fed by the orchestration event stream, and an audit chain verified on
demand. No traces, no metric exporter, no alerts, and no ids in the log lines of the worker, the
outbound delivery or the scheduled jobs. The deployment target is one modest server running the API.

## Decision

### L — Logs

**L1 — Every line of a message's path carries its ids.** `tenantId`, `agentId`, `conversationId`
and `invocationId` (`LogKeys`) go into the logging context (MDC) where that work runs: ingest, the
worker, the orchestrator, the outbound delivery, the owner notices, the daily summaries, the audit
drain. Structured logs emit them as fields; with tracing, `traceId` and `spanId` join them.

**L2 — Ids, never content.** No log line carries a message's text, an identity's value, a prompt or
a provider's error detail, which can echo the prompt. A failure on the message path is logged by
its type (`LogSafe`): for a provider failure, with the provider and model; for one of Cauce's own
exceptions, with its message, which by convention names ids; anything else, a database error
included, by its type only. The detail stays where it already is (the invocation's `last_error`
and SYSTEM message, erasable with the conversation) and in DEBUG logs, off in production.

### T — Traces

**T1 — OpenTelemetry, exported over OTLP only.** The application emits traces, metrics and logs
over OTLP and knows nothing of the backend. The chosen backend is OpenObserve: one container,
traces, metrics and logs, alerts with webhook destinations, local storage, about 300–500 MB of RAM
on the API's server. Replacing it is configuration.

**T2 — One trace per message, end to end.** The trace of the request that brings a message in
(the webhook or the API call) is continued by the invocation that answers it: ingest stores the
W3C `traceparent` with the pending invocation, and the worker resumes it, so the channel's input,
the orchestration, every model and tool call and the outbound delivery are one trace.

**T3 — Span attributes are ids and closed vocabularies.** An allow-list: ids (tenant, agent,
conversation, invocation, message, channel config), provider, model, tool name, channel type,
round, token counts, finish reason, outcome, failure type, and the HTTP server's own. Never content,
identity values, prompts or tool arguments. An error is recorded by its type, never its message.
A test with an in-memory exporter fails on any attribute outside the list or any value that holds
the message's text.

### M — Metrics

**M1 — Tags: tenant and agent, never conversation or identity.** `tenant_id` and `agent_id` are
tags where they say something (invocations, fallbacks, turn latency, cost, gaps between messages);
`conversation_id`, identity values and message ids never are: one time series each would explode the
store. Two agents per bar make this fine for tens of bars; **to be reviewed from a few hundred**.

**M2 — Cross-tenant state through one narrow function.** The queue (pending invocations and the
oldest one's age), the audit outbox (pending rows and the oldest one's age), owner notices not sent,
spend alerts and each client's spend this month are read every minute through one `SECURITY
DEFINER` function returning aggregates only, never rows (ADR 0001), and published as gauges.

### A — Alerts and checks

**A1 — Alerts come from the backend, to the operator's own Telegram bot.** OpenObserve evaluates
the rules and sends to a Telegram bot of the operator's, not one of a bar's; its token lives in the
backend's configuration, never in Cauce. An application that is down cannot send its own alert.
The minimum rules: provider down, fallback rate high, queue stuck, audit chain not being written,
spend limit reached.

**A2 — The audit chain: lag in the application, full verification outside it.** Verification of
the chain stays on demand only (CLAUDE.md, design rules). The application alerts on what precedes
a broken chain: the drain falling behind and errors writing it. The full periodic verification is
done by the independent verifier (`tools/audit-chain-verifier`) against a dump, scheduled by the
operator outside Cauce; the runbook says how.

**A3 — A server that is down entirely is caught from outside.** A cron on the server pings
Healthchecks when `/actuator/health` answers; if the pings stop, Healthchecks notifies. That
endpoint, unauthenticated, answers the status only, never details.

## Consequences

- The operator learns of the five failures above on the phone and follows any one message from
  its ids, across logs and one trace, without seeing what was said.
- One more container on the server, and the application's export, a few tens of MB.
- Logs lose the provider's error text; whoever needs it reads the invocation's record.
- Tags by tenant and agent are a cost that grows with the bars; M1 says when to look again.

## Verification

Verified end to end on 2026-10-23, locally (there is no Cauce server yet), against OpenObserve
v1.0.4 and the operator's own Telegram bot: a provider down (an invalid key) raised
`cauce-provider-down` and `cauce-fallback-rate`; a high fallback rate with the provider healthy (a
client with a limit and an unpriced model) raised only `cauce-fallback-rate`, one alert per bar; a
message's trace was one trace from the request to the model call, its logs carried its ids, and the
dashboard showed it. No message text reached traces, logs or alerts.

What the check changed, recorded in the commits and the runbook: failed model calls are counted
directly (`LlmFailed`), since OpenObserve's PromQL returns nothing for a missing series; counters are
exported as deltas and summed over a window, since `increase()` reads zero for a series that appears
already counting; scheduled jobs are not traced; and each series alerts and is silenced on its own,
so one bar's alert never silences another's (`scripts/observability/test-alerts.sh`).

Pending until a server exists: the Healthchecks check (A3), configured and paused, and the nightly
run of the audit chain verifier (A2), both in the runbook.

## References

- ADR 0001 — RLS escape hatches (M2)
- ADR 0005 — Agentic loop hardening (the provider's detail kept with the invocation)
- ADR 0010 — Usage, prices and spend limits (the spend alerts, the gap metric)
- [`docs/operations/observability.md`](../operations/observability.md) — the runbook
