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
- **Eliding past turns' tool results from the context (R1): measured and rejected, the switch kept.** Behind a per-agent switch, off by default (`agents.elide_past_lookups`, V45, `PATCH /v1/agents/{id}`): before the latest USER message, the result of a call to a lookup tool (`Tool.lookup()`: the profile, the menu, availability) is replaced by a marker; the call and its paired result stay, and actions' results are never elided. On the full battery (N = 5, `2026-10-23T0914Z` against `0941Z`) the pass rate stayed at 100 %, input tokens per model turn fell 4 % and cost rose 3.7 %, because rewriting the previous turn breaks its cached prefix (ADR 0005, amendment of 2026-10-23). Reopens with production data, where the cache goes cold between a person's messages; turning it on is then a `PATCH`, and if it never pays off the switch and V45 are removed.
- **A new thread after inactivity (R2).** An OPEN conversation never closes on its own, so a person who comes back days later carries the whole history. Decided for after the eval harness: close and start a new thread after a period without messages, 24 h by default, configurable per agent. Condition: the person's open bookings and cases must be readable through tools (`list_my_bookings` already lists bookings; open cases have no tool yet), so nothing depends on the history; verified by a scenario "the customer comes back the next day".
- **Operator access to the failure diagnostic.** Since ADR 0005 (C3) the public messages endpoint hides SYSTEM `[orchestration_error]` records and a FALLBACK message carries only the public failure reason; the provider detail lives in the SYSTEM text and `pending_invocations.last_error`. Exposing it to operators belongs to the invocation status endpoint (`GET /v1/invocations/{id}`), under an operator-only view, and is not built.


## Ingest and idempotency

- **No body fingerprint on `Idempotency-Key`.** Replay matches by key only; the threat model is byte-identical webhook redelivery, not a client reusing a key with a different body.
- **No retention purge for `ingest_idempotency_records` and `channel_inbound_receipts`.** Both tables have `created_at` (stamped by the database's default) and an index, so a scheduled `DELETE WHERE created_at < now() - interval '…'` is a pure add; the cutoff is computed in SQL (ADR 0008). The retention must outlast the providers' redelivery horizon (Telegram and Meta retry for hours, not days), or a late redelivery would be handled twice.
- **The `AFTER_COMMIT`/outbox question for `OrchestrationEvent` consumers is parked.** `InvocationRequested` is published inside the ingest transaction (TODO in `InboundMessageService`). The in-memory metrics listener is exempt; the first persisting consumer must resolve it.

## Credentials

- **Per-tenant LLM credentials.** Only the system-default credential exists (`SystemDefaultLlmCredential`, env-var based); there is no per-tenant/BYO-key path. Gates the commercial model.
- **Rotation of the secrets encryption key.** Channel credentials are sealed with AES-GCM under `cauce.security.secrets-encryption-key` (ADR 0006, W3). There is one key and no rotation: changing it makes every sealed value unreadable. The `v1:` prefix leaves room for a key id and a re-sealing pass. Per-tenant LLM credentials, when they land, should reuse the same `SecretCipher` port.
- **The plaintext read path of channel credentials.** `ChannelConfigMapper` still reads an unsealed credential as-is (every write is sealed; the startup runner seals old rows). Once every deployment has run the runner, the read path and the runner go, and an unsealed value becomes an error.

## Business capabilities (ADR 0007)

- **Choosing an adapter per business.** Each business port (`BookingProvider`, `CatalogSource`, `BusinessProfileSource`, `HandoffSink`, `KnowledgeGapSink`) has one adapter, the native one. Selecting an external one per tenant (a String id validated by the service, like `model_provider`) arrives with the second adapter of a port (ADR 0007, B2).
- **An MCP client.** Where it fits is recorded in ADR 0007, B11; nothing is built.
- **Several venues of one business with a unified view.** One profile, catalog and agenda per CLIENT tenant; two venues are two tenants (B5).
- **The owner agent (phase 2).** Suppliers, accounts and staff. In phase 1 the owner bot only receives notices; its agent says so.
- **Templates for proactive messages on WhatsApp.** An owner notice outside the 24-hour window is recorded `NOT_SENT` with `outside_reply_window` (B9), the same rule as W4.
- **Owner-side booking actions.** The owner reads bookings over the API but cannot cancel or move one there; changes go through the customer's conversation.
- **A summary missed across midnight.** A nightly summary whose local day ends while the instance is down is not sent late (the scheduler only runs today's).
- **Retention of business rows.** Bookings, handoffs, unanswered questions, owner notifications, digests and used link codes have no purge; they hold personal data, so a retention policy per business is the follow-up. The purge's cutoff is SQL `now()` (ADR 0008), so the `created_at` it compares must be stamped by the database clock first: bookings and handoffs still take theirs from the JVM.

## Usage and cost

- **No usage aggregation by tenant.** Per-invocation usage is exposed on the invocation endpoint; there is no query across invocations.

## Audit trail (governance)

- **Export artefact and independent-verifier input.** A self-contained document with entries, signatures, the public keys needed and the `prev_hash` anchoring the start of a range. The reference verifier under `tools/audit-chain-verifier/` reads its own SQL-produced dump format until this lands, and the export may choose a different shape.
- **External time anchoring.** Publishing the chain head to an external medium (an open timestamping protocol, a qualified timestamping authority, or both) — the only thing that would make a `TRUNCATED` verdict issuable, which is why that verdict was withdrawn (`be5c178`).
- **Per-tenant signing keys.** Additive thanks to `key_id`.
- **Four reserved audit hooks, unemitted**: `conduct.external.action` (when a tool acts on an external system — the native business tools act on Cauce's own tables), `conduct.data.access` (when a tool reaches external data), `conduct.reply.delivered` (when delivery becomes transactional), `admin.credential.changed` (when per-tenant credentials land).
- **Actor attribution by API-key id.** Audit records carry the acting tenant, not the key; needs the principal plumbed from the auth filter. Goes with fine-grained authorization.
- **Retention purge of DRAINED outbox rows.** Raw payloads also live in `audit_outbox` until purged. Its `created_at` is JVM-stamped; the purge needs a database-clock cutoff (ADR 0008).

## Channels

- **Delivery guarantee.** Outbound delivery is best-effort: fire-and-forget on the `channelOutboundExecutor`, no outbox, no retries, no delivery dedup; failures are logged and the reply stays readable by polling. The `AgentReplyDispatcher` port carries the TODO: persist the delivery intent behind the same port and drain it from a scheduled dispatcher, additively.
- **Channel-config list and disable endpoints.** Only creation exists. Without the list, the demo-bar scripts, which find the business and its agents again by name when `.demo-bar.env` is lost, bind the bots anew (the previous config of the same bot stays active, unused).
- **`SUPPORTED_CHANNELS` by SPI.** `ConversationService` validates `channel_type` against a hardcoded set; replacing it with the adapter registry needs a port, since a direct dependency would be a cycle.
- **WhatsApp templates.** Outside the 24-hour customer service window a reply, or a fallback, is not sent: a loud failure (ADR 0006, W4). Sending approved templates there is a later unit.
- **WhatsApp identity joining.** A changed number or BSUID (`system` webhook) starts a new identity and thread, with a WARN; joining the two is the memory subject ADR 0004 defers. The parent BSUID (`parent_user_id`, across portfolios) is not read.
- **Per-agent text-only reply.** The answer to a non-text WhatsApp message is the platform's `cauce.channels.whatsapp.unsupported-message-reply`; a per-agent override like the fallback's (ADR 0005, C3) would be a second per-agent column and write path (ADR 0006, W5).
- **WhatsApp group messages.** Refused with a WARN when the message carries `group_id`; whether that is the field Meta's Groups API uses for every group event is to be confirmed against a real group.
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

- `cauce-enterprise` contains only a `package-info.java`.
- Vector retrieval in `cauce-memory`: pgvector is enabled; no code uses it.
- Helm chart for Kubernetes deployments: not in the repo.
