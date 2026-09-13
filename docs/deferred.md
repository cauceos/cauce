# Deferred work

**This file is state, and it expires.** It registers work that was consciously deferred — a backlog record, not a commitment to build these next. Every entry here was true when written and stops being true the moment a unit lands it. The rule that keeps it honest: **the commit that lands an item removes its entry.** Verify against the code before relying on any line.

Its intended destination is GitHub issues labelled `deferred`, where an open issue is the record and a closed one is the reconciliation. Until that migration, this file is the register. Moved out of `CLAUDE.md` on 2026-09-13 so the file loaded into every session stops carrying claims that go stale.

Entries are grouped by area. Each says what is missing and, where it matters, why it was deferred.

## Agentic loop and tools

- **Per-agent tool scoping.** Every registered tool is offered to every agent. The registry is global.
- **A heartbeat instead of the flat reaper timeout.** The orphan timeout is a fixed duration sized for the multi-step loop; a long-running loop would need a liveness signal rather than a bigger constant.
- **Counting the tool-definition schema against the context window.** Only tool-message content is counted; the schema the provider receives is not.
- **Per-model limits beyond the context window.** Only the context window has a registry plus a conservative fallback (`ModelContextWindow`); max response tokens is a flat default, never per-model.
- **`RESERVED_FOR_RESPONSE`** is a hardcoded token constant in `ContextBuilder`; revisit when tuning context assembly.
- **Streaming** is not part of the LLM SPI — explicitly deferred past v1.0, to be added as a separate method. The orchestrator does one blocking `invoke`.
- **No conversation-visible trace for some failures.** Reaper-abandoned invocations and non-LLM setup failures append no SYSTEM `[orchestration_error]` message to the conversation (only LLM provider failures and the tool-iteration cap do), so their only client-visible signal is the invocation status.

## Ingest and idempotency

- **No body fingerprint on `Idempotency-Key`.** Replay matches by key only; the threat model is byte-identical webhook redelivery, not a client reusing a key with a different body.
- **No retention purge for `ingest_idempotency_records`.** The table has `created_at` and an index, so a scheduled `DELETE WHERE created_at < …` is a pure add.
- **The `AFTER_COMMIT`/outbox question for `OrchestrationEvent` consumers is parked.** `InvocationRequested` is published inside the ingest transaction (TODO in `InboundMessageService`). The in-memory metrics listener is exempt; the first persisting consumer must resolve it.

## Credentials

- **Per-tenant LLM credentials.** Only the system-default credential exists (`SystemDefaultLlmCredential`, env-var based); there is no per-tenant/BYO-key path. Gates the commercial model.
- **Channel credential encryption at rest.** The provider credential (e.g. a bot token) on `ChannelConfig` is stored in plaintext (TODO on the record), to be solved consistently with per-tenant LLM credentials.

## Usage and cost

- **No usage aggregation by tenant.** Per-invocation usage is exposed on the invocation endpoint; there is no query across invocations.
- **No price table and no cost view.** Deliberate: cost must be derivable from the usage facts and a versioned price table, never stored (see the design rules in `CLAUDE.md`).

## Audit trail (governance)

- **Export artefact and independent-verifier input.** A self-contained document with entries, signatures, the public keys needed and the `prev_hash` anchoring the start of a range. The reference verifier under `tools/audit-chain-verifier/` reads its own SQL-produced dump format until this lands, and the export may choose a different shape.
- **External time anchoring.** Publishing the chain head to an external medium (an open timestamping protocol, a qualified timestamping authority, or both) — the only thing that would make a `TRUNCATED` verdict issuable, which is why that verdict was withdrawn (`be5c178`).
- **Per-tenant signing keys.** Additive thanks to `key_id`.
- **Six reserved audit hooks, unemitted**: `conduct.external.action` (when a side-effect tool exists), `conduct.data.access` (when a tool reaches external data), `conduct.human.escalation` (when `escalateConversation` gets a caller), `conduct.reply.delivered` (when delivery becomes transactional), `admin.agent.updated` (when an agent-update operation exists), `admin.credential.changed` (when per-tenant credentials land).
- **Actor attribution by API-key id.** Audit records carry the acting tenant, not the key; needs the principal plumbed from the auth filter. Goes with fine-grained authorization.
- **Retention purge of DRAINED outbox rows.** Raw payloads also live in `audit_outbox` until purged.

## Channels

- **Delivery guarantee.** Outbound delivery is best-effort: fire-and-forget on the `channelOutboundExecutor`, no outbox, no retries, no delivery dedup; failures are logged and the reply stays readable by polling. The `AgentReplyDispatcher` port carries the TODO: persist the delivery intent behind the same port and drain it from a scheduled dispatcher, additively.
- **`channel_config_id` on conversations.** The column does not exist, so a conversation whose (agent, channel) has more than one ACTIVE config skips outbound delivery with a WARN: the originating bot is ambiguous.
- **Channel-config list and disable endpoints.** Only creation exists.
- **`SUPPORTED_CHANNELS` by SPI.** `ConversationService` validates `channel_type` against a hardcoded set; replacing it with the adapter registry needs a port, since a direct dependency would be a cycle.
- **WhatsApp adapter.** The SPI was designed against it (HMAC over the raw body in `verify`); no adapter exists.
- **Outbound delivery metrics.** Log-only; an outbound event would let observability count it.
- **Typed channel identity, message types beyond text, and per-channel capabilities** are open decisions, not deferred implementation — recorded with their cost in the private ADR-006.

## API

- **`GET /v1/tenants/{id}/api-keys` is an unpaginated bare array** — the only list endpoint outside the uniform keyset contract. Align it when it is next touched.
- **`api_keys.last_used_at`** is updated synchronously, but only on the auth cold path (cache hits skip the UPDATE; staleness is bounded by the cache TTL). An async batched update is deferred (TODO in `ApiKeyAuthenticationFilter`).
- **Fine-grained authorization.** API keys carry no scopes or roles (empty authorities); deferred until a concrete need exists. Authorization is tenant scoping via RLS only.
- **Backend CORS configuration.** The API has no CORS config, so browsers block cross-origin calls; the playground bridges it with its dev-server proxy. When a browser client must target instances without the proxy, add property-driven CORS to cauce-api (`cauce.api.cors.allowed-origins`, default empty = disabled) as its own unit.

## Observability

- **Events outside the invocation path.** Tenancy and API operations emit no `OrchestrationEvent`-style events.
- **Distributed traces.** No OpenTelemetry dependency.
- **A metric exporter** (OTLP or Prometheus). Metrics exist in-process via Micrometer only.

## Frontend

- **The product dashboard** (`frontend/cauce-dashboard`, Angular) has not been started. The React playground is a developer testing tool, not the dashboard.
- **A frontend test harness for the playground.** Its gate is `npm run build` plus manual browser verification, by deliberate choice of its first unit.

## Modules not started

- `cauce-evals` and `cauce-enterprise` contain only a `package-info.java`.
- Vector retrieval in `cauce-memory`: pgvector is enabled; no code uses it.
- Helm chart for Kubernetes deployments: not in the repo.
