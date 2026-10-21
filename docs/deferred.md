# Deferred work

**This file is state, and it expires.** It registers work that was consciously deferred — a backlog record, not a commitment to build these next. Every entry here was true when written and stops being true the moment a unit lands it. The rule that keeps it honest: **the commit that lands an item removes its entry.** Verify against the code before relying on any line.

Its intended destination is GitHub issues labelled `deferred`, where an open issue is the record and a closed one is the reconciliation. Until that migration, this file is the register. Moved out of `CLAUDE.md` on 2026-09-13 so the file loaded into every session stops carrying claims that go stale.

Entries are grouped by area. Each says what is missing and, where it matters, why it was deferred.

## Agentic loop and tools

- **A heartbeat instead of the flat reaper timeout.** The orphan timeout is a fixed duration sized for the multi-step loop; a long-running loop would need a liveness signal rather than a bigger constant.
- **Counting the tool-definition schema against the context window.** Only tool-message content is counted; the schema the provider receives is not.
- **Per-model limits beyond the context window.** Only the context window has a registry plus a conservative fallback (`ModelContextWindow`); max response tokens is a flat default, never per-model.
- **`RESERVED_FOR_RESPONSE`** is a hardcoded token constant in `ContextBuilder`; revisit when tuning context assembly.
- **Streaming** is not part of the LLM SPI — explicitly deferred past v1.0, to be added as a separate method. The orchestrator does one blocking `invoke`.
- **Operator access to the failure diagnostic.** Since ADR 0005 (C3) the public messages endpoint hides SYSTEM `[orchestration_error]` records and a FALLBACK message carries only the public failure reason; the provider detail lives in the SYSTEM text and `pending_invocations.last_error`. Exposing it to operators belongs to the invocation status endpoint (`GET /v1/invocations/{id}`), under an operator-only view, and is not built.

## Ingest and idempotency

- **No body fingerprint on `Idempotency-Key`.** Replay matches by key only; the threat model is byte-identical webhook redelivery, not a client reusing a key with a different body.
- **No retention purge for `ingest_idempotency_records`.** The table has `created_at` and an index, so a scheduled `DELETE WHERE created_at < …` is a pure add.
- **The `AFTER_COMMIT`/outbox question for `OrchestrationEvent` consumers is parked.** `InvocationRequested` is published inside the ingest transaction (TODO in `InboundMessageService`). The in-memory metrics listener is exempt; the first persisting consumer must resolve it.

## Credentials

- **Per-tenant LLM credentials.** Only the system-default credential exists (`SystemDefaultLlmCredential`, env-var based); there is no per-tenant/BYO-key path. Gates the commercial model.
- **Rotation of the secrets encryption key.** Channel credentials are sealed with AES-GCM under `cauce.security.secrets-encryption-key` (ADR 0006, W3). There is one key and no rotation: changing it makes every sealed value unreadable. The `v1:` prefix leaves room for a key id and a re-sealing pass. Per-tenant LLM credentials, when they land, should reuse the same `SecretCipher` port.
- **The plaintext read path of channel credentials.** `ChannelConfigMapper` still reads an unsealed credential as-is (every write is sealed; the startup runner seals old rows). Once every deployment has run the runner, the read path and the runner go, and an unsealed value becomes an error.

## Usage and cost

- **No usage aggregation by tenant.** Per-invocation usage is exposed on the invocation endpoint; there is no query across invocations.
- **No price table and no cost view.** Deliberate: cost must be derivable from the usage facts and a versioned price table, never stored (see the design rules in `CLAUDE.md`).

## Audit trail (governance)

- **Export artefact and independent-verifier input.** A self-contained document with entries, signatures, the public keys needed and the `prev_hash` anchoring the start of a range. The reference verifier under `tools/audit-chain-verifier/` reads its own SQL-produced dump format until this lands, and the export may choose a different shape.
- **External time anchoring.** Publishing the chain head to an external medium (an open timestamping protocol, a qualified timestamping authority, or both) — the only thing that would make a `TRUNCATED` verdict issuable, which is why that verdict was withdrawn (`be5c178`).
- **Per-tenant signing keys.** Additive thanks to `key_id`.
- **Five reserved audit hooks, unemitted**: `conduct.external.action` (when a side-effect tool exists), `conduct.data.access` (when a tool reaches external data), `conduct.human.escalation` (when `escalateConversation` gets a caller), `conduct.reply.delivered` (when delivery becomes transactional), `admin.credential.changed` (when per-tenant credentials land).
- **Actor attribution by API-key id.** Audit records carry the acting tenant, not the key; needs the principal plumbed from the auth filter. Goes with fine-grained authorization.
- **Retention purge of DRAINED outbox rows.** Raw payloads also live in `audit_outbox` until purged.

## Channels

- **Delivery guarantee.** Outbound delivery is best-effort: fire-and-forget on the `channelOutboundExecutor`, no outbox, no retries, no delivery dedup; failures are logged and the reply stays readable by polling. The `AgentReplyDispatcher` port carries the TODO: persist the delivery intent behind the same port and drain it from a scheduled dispatcher, additively.
- **Channel-config list and disable endpoints.** Only creation exists.
- **`SUPPORTED_CHANNELS` by SPI.** `ConversationService` validates `channel_type` against a hardcoded set; replacing it with the adapter registry needs a port, since a direct dependency would be a cycle.
- **WhatsApp adapter.** The SPI was designed against it (HMAC over the raw body in `verify`); no adapter exists.
- **Outbound delivery metrics.** Log-only; an outbound event would let observability count it.
- **Message parts and per-channel capabilities** are ADR 0004's second and third units: decided, not yet implemented. Typed identity (the first unit) has landed.
- **Telegram groups.** A group `chat.id` is not a person and there is no `GROUP` identity kind (ADR 0004, conscious limitation): the adapter accepts private chats only and refuses the rest with a WARN.

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
- **Chain break and failure states in `--color-danger`.** `.interface-design/system.md` makes danger (`#C8664B` + `--color-danger-bg`) the error signal, always with icon and label; neither token exists in `tokens.css` yet, and the playground still paints the broken chain link and failure marks in slate (`audit.css`, `conversation.css`). The same unit covers revoked keys and failed invocations.
- **UI copy into the `src/i18n/` catalogue.** User-facing strings are literals in the components; `system.md` requires them in a per-locale catalogue (`en` only) so adding Spanish is one file. The vocabulary guard already scans `src/i18n/`.

## Modules not started

- `cauce-evals` and `cauce-enterprise` contain only a `package-info.java`.
- Vector retrieval in `cauce-memory`: pgvector is enabled; no code uses it.
- Helm chart for Kubernetes deployments: not in the repo.
