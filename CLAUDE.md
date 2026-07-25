# CLAUDE.md

This file provides project context for Claude Code and any human contributor working on Cauce. Read this before making changes.

## Project overview

Cauce is the open-source Agent OS for European businesses. It is a Java-native platform for building, operating, and governing AI agents in production. Multi-tenant from the first commit, sovereign by design, with first-class support for voice and chat channels.

The project is in early development and built in the open. Foundational architecture is being established. Public-facing technical documentation will be published as the codebase stabilizes.

See [README.md](README.md) for the user-facing project description.

## Tech stack

**Backend**
- Java 21 LTS
- Spring Boot 3.x
- Gradle with Kotlin DSL (multi-module project)
- PostgreSQL 16+ with pgvector extension
- Redis for cache and ephemeral state (provisioned in dev; no production code uses it yet — the only cache today is the in-process Caffeine API-key cache)
- Spring Application Events for internal eventing (planned, not yet used)
- OpenTelemetry for observability (planned; no OTel dependency yet)
- JUnit 5, Mockito, AssertJ, Testcontainers for testing

**Frontend** (planned — `frontend/` does not exist yet)
- Angular 17+ with standalone components and Signals
- TypeScript 5+ (strict mode)
- Tailwind CSS
- Angular CLI for build and dev server
- Jasmine + Karma for unit tests, Playwright for e2e

**Infrastructure**
- Docker + Docker Compose for development
- Helm chart for Kubernetes production deployments (planned, not in the repo yet)
- GitHub Actions for CI/CD

## Repository structure

> **Current state**: backend/ contains 15 Gradle subprojects. Implemented so far: the domain and persistence layers with hierarchical RLS (Flyway migrations V1–V21), tenancy application services, API-key authentication (HMAC-SHA256), an async LLM invocation engine (queue, context assembly, worker/reaper, inbound message ingest with optional idempotency-key deduplication), two LLM adapter modules (native Anthropic; OpenAI-compatible covering OpenAI, Mistral, and Ollama), an authenticated REST API including a public messaging endpoint (202 with `invocation_id`, an invocation-status endpoint with a public failure vocabulary backed by the V17 persisted failure taxonomy, and uniform keyset pagination on the three list endpoints), the end-to-end agentic tool loop (the neutral tool model in `cauce-core`, the executable tool SPI + built-in clock in `cauce-tools`, tool-message persistence in `cauce-memory`, the `cauce-llm` contract and both adapters mapping tools to each provider's wire format, and the orchestrator's bounded dispatch-and-feed-back loop), the invocation lifecycle event contract (`cauce-orchestration-events`, emitted synchronously from the loop) with its first consumer (Micrometer metrics in `cauce-observability`, exposed via the authenticated `/actuator/metrics`; no exporter yet), the per-tenant LLM usage ledger (V19 `llm_usage_records`: one immutable row per LLM call, written synchronously by the orchestrator before `LlmResponded` — capture only, no query endpoint or pricing yet), and the channel layer in `cauce-channels`: the complete channel SPI (inbound + outbound halves), the `ChannelConfig` agent/tenant binding (V16, RLS + SECURITY DEFINER webhook resolution), and the full Telegram round-trip (webhook → normalization → idempotent ingest, and best-effort outbound delivery of the agent reply via the `AgentReplyDispatcher` port in cauce-core — explicit port, not an event consumer). `cauce-governance` now holds the guaranteed-capture audit skeleton: the transactional `audit_outbox` (V20, written in the caller's business tx via the `AuditEventRecorder` port), the append-only `audit_log_entries` ledger (V21 — UPDATE/DELETE revoked from `cauce_app` at the grant layer; hash/signature columns reserved, null), and the per-tenant scheduled drainer (contiguous per-tenant `sequence_number`); no production callers or hash chain yet. `cauce-evals` and `cauce-enterprise` are empty skeletons. docker-compose.yml provides local PostgreSQL + pgvector + Redis + Adminer for development. The frontend has not been started. Last build at reconciliation (2026-07-25): 743 tests, 0 failures.

**Backend modules** (Gradle subprojects under `backend/`; the Gradle build — `settings.gradle.kts`, wrapper, `gradle/` — lives under `backend/`, not the repo root):

- `cauce-core` — domain model: `Tenant`, `Agent`, `Conversation`, `Message`, `ApiKey` aggregates, the neutral tool model (`ToolDefinition`, and the sealed `ToolContent` = `ToolCall` | `ToolResult`), `MessageRole` (incl. `TOOL_CALL`/`TOOL_RESULT`), `TenantContext`, UUIDv7 generation, API-key hashing ports; no framework dependencies (its only third-party library is uuid-creator)
- `cauce-memory` — persistence: JPA entities, hand-written mappers, Spring Data repositories, `RlsContextAspect`, Flyway migrations (V1–V21 — all modules' migrations live here, incl. the messages `tool_content` jsonb column, the `ingest_idempotency_records` table, the `llm_usage_records` ledger, and the governance `audit_outbox`/`audit_log_entries` pair). Vector retrieval is planned (pgvector enabled, no code yet)
- `cauce-channels` — channel adapter SPI (invariant 3) and reference adapters. The SPI is complete: `InboundChannelAdapter` (`verify` webhook authenticity against the config — header token or body HMAC — then `parse` the provider payload into the neutral `ChannelInboundMessage`) and `OutboundChannelAdapter` (`deliver(ChannelOutboundMessage, config)`), with `ChannelAdapterRegistry` mirroring the LLM/tool registries (both halves; a channel may implement only one). `ChannelConfig` binds a channel instance (e.g. one Telegram bot) to an agent: vertical slice (domain + JPA persistence + services) mirroring `PendingInvocation`; rows carry the agent's owning `tenant_id` for RLS, the provider `credential` (plaintext at rest, encryption TODO) and the hash of the server-generated webhook secret (plaintext returned once, mirroring API keys). The unauthenticated webhook path resolves a config — and discovers the tenant — via the V16 `SECURITY DEFINER` function (ADR 0001), then ingests under RLS through `InboundMessageService`. First adapter: **Telegram, both halves** — inbound (`update.message.text`; idempotency key `configId + ":" + update_id`, so provider redelivery replays instead of duplicating) and outbound (`sendMessage` via the JDK HttpClient, bot token from the config credential). Outbound delivery: `OutboundDeliveryService` implements the `AgentReplyDispatcher` port (cauce-core) — the orchestrator invokes the abstraction after the final AGENT append commits (explicit port, deliberately NOT an event consumer; the event stream stays observational); delivery is best-effort on the dedicated `channelOutboundExecutor` (tenant context captured and re-established; failures logged and swallowed; a channel without an outbound half, like `api`, is a clean no-op; ≠1 ACTIVE configs for the conversation's (agent, channel) skips with a WARN — the originating config is not stamped on conversations yet). Depends on core, memory, orchestration; only cauce-api depends on it
- `cauce-llm` — provider-neutral LLM SPI: `LlmProvider`, `LlmProviderRegistry`, credentials, and the neutral invocation/response model, which carries the `cauce-core` tool model (`LlmInvocation.tools`, `LlmMessage` tool content, `LlmResponse.toolCalls`, `FinishReason.TOOL_USE`). Depends on `cauce-core`. Adapters live in separate modules
- `cauce-llm-anthropic` — native Anthropic adapter (`POST /v1/messages`); maps the neutral tool model to/from Anthropic's `tool_use`/`tool_result` content blocks. Its bean is registered only when an Anthropic API key is configured
- `cauce-llm-openai` — single OpenAI-compatible adapter (`POST /chat/completions`) mapping tools to/from the `tools`/`tool_calls`/`role:"tool"` format (`arguments` as a JSON string), registered as three conditional providers: `ollama` (keyless, dev default), `openai`, `mistral`
- `cauce-tools` — executable tool SPI: the `Tool` contract (`definition()` + `execute(ToolCall)`), a Spring-managed `ToolRegistry` mirroring `LlmProviderRegistry`, and the built-in `get_current_time` clock tool (injectable `java.time.Clock`). Depends only on `cauce-core` (plus spring-context); the neutral tool model lives in core. Global registry; per-agent tool scoping is deferred. Format mapping in the adapters (B) and the orchestrator loop (C) are not built yet
- `cauce-evals` — evaluation framework, conversation testing, regression detection — empty skeleton, not started
- `cauce-observability` — observability layer; first content: `OrchestrationMetrics`, the first consumer of the orchestration event stream — a guarded `@EventListener` translating the 8 `OrchestrationEvent`s into Micrometer meters (`cauce.orchestration.*`: invocation counters incl. `failure_type`, LLM calls/responses and token usage by `provider`/`model`, tool-call counters and an execution timer; low-cardinality tags only, ids never become tags). Pure consumer: the whole dispatch is try/caught so a metrics failure can never break the synchronous publication path. Depends only on `cauce-orchestration-events`, spring-context, and micrometer-core; the `MeterRegistry` is wired by cauce-api's Actuator. Traces (OTel) and exporters (OTLP/Prometheus) are planned, separate units
- `cauce-governance` — immutable audit log, RGPD endpoints, policy engine, AI Act compliance. First content: the guaranteed-capture skeleton of the audit trail (`dev.cauce.governance.audit` + `persistence`). `AuditEventRecorder` (port) + `OutboxAuditEventRecorder` (`@Transactional(MANDATORY)` — one INSERT that joins the caller's business tx, so capture commits or rolls back with the fact it audits; throws outside a tx); `audit_outbox` (V20: RLS, `(tenant_id, drain_status, created_at)` index, `audit_outbox_pending_tenants()` SECURITY DEFINER discovery — ADR 0001); the **append-only** `audit_log_entries` ledger (V21: `REVOKE UPDATE, DELETE ... FROM cauce_app` — enforced by role at the DB, not by code; `UNIQUE (tenant_id, sequence_number)` + `UNIQUE (outbox_id)`; `prev_hash`/`entry_hash`/`signature` created nullable, RESERVED for the hash-chain unit); and `AuditOutboxDrainer`/`AuditOutboxDrainService` (worker-pattern `@Scheduled` cousin, `cauce.governance.audit.drainer.*`, default 5s/batch 100: per tenant under its `TenantContext`, one tx per batch assigning the contiguous per-tenant sequence from MAX+1 and flipping PENDING→DRAINED — restart-safe). `event_type` is a semantics-free placeholder; no production callers yet (wiring the loop/tenancy, the hash chain, and outbox retention are follow-up units). Depends on `cauce-core` + `cauce-memory` only (cauce-tenancy is test-scope); cauce-api already depended on it
- `cauce-tenancy` — application services for tenants, agents, conversations, messages, and API keys; operator bootstrap; HMAC-SHA256 API-key hashing with a Caffeine cache
- `cauce-orchestration` — async invocation engine and the bounded agentic tool loop: pending-invocation queue, context assembly with a per-model context-window registry (`ModelContextWindow`; conservative 16,384-token fallback with a `WARN` for unknown models) that renders tool messages, the orchestrator loop (offers all registered tools, dispatches tool calls via the `cauce-tools` `ToolRegistry`, feeds results back, capped at 10 iterations — tool failures feed back as errored results, the cap fails the invocation), background worker/reaper (12-minute orphan timeout sized for the multi-step loop), and `InboundMessageService` (the inbound message ingest unit, with optional idempotency-key deduplication: an insert-first lock on the V15 `(agent_id, idempotency_key)` unique constraint inside the single ingest transaction; a replay returns the stored result with no second message, invocation, or `InvocationRequested` event). Also owns the per-tenant LLM usage ledger (`usage` package: `LlmUsageRecord` + `LlmUsageRecorder`; V19 `llm_usage_records`, tenant_id + RLS like `PendingInvocation`): one immutable row per LLM call (provider, model, round_index, tokens, finish_reason), written synchronously in its own short transaction between the provider response and the `LlmResponded` publication — a failed usage INSERT fails the invocation, so billing facts are never lost silently; cost is deliberately not materialized (pricing will be a versioned table + view, a future unit). Depends on `cauce-tenancy`, `cauce-tools`, and `cauce-orchestration-events`. Publishes the invocation lifecycle events synchronously via `ApplicationEventPublisher` at each step of the loop (ingest, context assembly, each LLM call/response with token usage, each tool dispatch, completion, permanent failure)
- `cauce-orchestration-events` — the invocation lifecycle event contract: sealed `OrchestrationEvent` with 8 immutable records (`InvocationRequested`, `ContextAssembled`, `LlmInvoked`, `LlmResponded`, `ToolCallRequested`, `ToolExecuted`, `InvocationCompleted`, `InvocationFailed` + `InvocationFailureType`). Leaf module with zero dependencies (plain JDK payloads; no Spring) so future consumers (observability, governance, usage accounting) can listen without depending on orchestration internals. No consumers exist yet; the stream is at-least-once, and `InvocationRequested` is published inside the ingest transaction (persisting consumers must use `AFTER_COMMIT` or an outbox — TODO in `InboundMessageService`)
- `cauce-api` — REST API surface; the Spring Boot application module. Compiles against the `cauce-llm` SPI only and wires both LLM adapters plus `cauce-tools` as `runtimeOnly` (the built-in tools register via the `dev.cauce` component scan)
- `cauce-enterprise` — commercial modules under separate license — empty skeleton

**Frontend** (planned — `frontend/` does not exist yet):

- `cauce-dashboard` — operator interface for managing workspaces, agents, conversations, costs

**Other top-level directories**:

- `.github/` — GitHub workflows (CI), Dependabot config, repo assets (no issue templates yet)
- `docs/` — public documentation; currently the ADRs under `docs/adr/`

## Architectural invariants

These rules are non-negotiable. Every change must respect them.

### 1. Multi-tenant from the first commit

Every domain entity carries tenant context. Every query is scoped by tenant. There is no concept of tenant-less data in this system.

The tenancy model is hierarchical with three fixed levels:

- **Level 0 — Operator**: the entity hosting a Cauce instance
- **Level 1 — Partner**: consultancy, agency, or integrator serving its own clients
- **Level 2 — End client**: the business whose users interact with agents

Visibility is hierarchical: an operator sees all its partners and their clients; a partner sees its own clients; an end client sees only its own data. Implementation uses PostgreSQL Row-Level Security with a `parent_tenant_id` column on the `tenants` table.

### 2. Hexagonal architecture

The domain core (`cauce-core`) does not know about HTTP, PostgreSQL, WhatsApp, or any specific LLM provider. It defines ports (interfaces); other modules implement adapters.

When adding a new feature, ask: does this belong in the domain, in an adapter, or in an application service? The domain stays clean and dependency-free.

### 3. Plugin SPI for channels and LLMs

Channels and LLM providers are pluggable. The core never imports a specific provider. New channels (Telegram, Discord, RCS) and LLM providers can be added without modifying core code.

When implementing a channel or LLM adapter, fulfill the SPI contract defined in `cauce-channels` or `cauce-llm`. Never leak provider-specific concepts into the core.

### 4. Observable by default

Every meaningful action emits a structured event. Tracing, metrics, and logs are first-class concerns, not afterthoughts.

When writing a new service method, ask: what events does this emit? What spans does it open? What metrics does it update?

### 5. Core vs Enterprise boundary

Modules under BUSL license (everything except `cauce-enterprise`) must be functional and complete on their own. Enterprise modules are extensions that monetize advanced capabilities (SSO/SAML, white-labeling, advanced multi-tier features).

Core functionality must never depend on enterprise modules. Core-required features must never be moved behind the enterprise license.

## Adding a new domain entity with hierarchical RLS

When introducing a new domain entity that participates in the tenant hierarchy, follow this pattern (proven across Tenant, Agent, Conversation, Message, and ApiKey; PendingInvocation applies the same RLS approach but keeps its domain and persistence inside `cauce-orchestration`). It keeps the hexagonal boundaries clean and makes tenant isolation enforceable at the database layer.

### 1. Domain layer (`cauce-core/<entity>/`)

- Pure POJO with private final fields and no JPA or framework annotations.
- Static factory methods that mint a UUIDv7 via `UuidGenerator.newV7()` (never call the UUID library directly).
- The factory validates only domain invariants: non-null/non-blank for required fields and basic shape. Everything that needs other rows or external config is validated in the service.
- Configuration-like fields whose valid values will eventually be owned by an SPI (e.g. provider identifiers for `cauce-llm`, channel types for `cauce-channels`) are `String`, not `enum`, so the core stays free of provider-specific knowledge.
- Status fields are `enum` when they are part of the immutable domain model (lifecycle states).
- Domain exceptions (extending `RuntimeException`) live in `cauce-core/<entity>/` next to the aggregate.

### 2. Persistence layer (`cauce-memory/<entity>/`)

- A JPA `@Entity` mirroring the domain shape, plus a hand-written `@Component` mapper (no MapStruct yet).
- A Spring Data `JpaRepository` with derived finders, added only when a query is actually needed.
- A Flyway migration `V<N>__create_<entity>_table.sql`:
  - Table with appropriate columns and constraints.
  - FK to the parent entity with `ON DELETE RESTRICT`.
  - `CHECK` constraints for enum-mapped columns (e.g. `status`), but not for SPI-bound columns (e.g. `provider`, `channel_type`) — those are validated by the service so the database does not hardcode the list.
  - Indexes on the parent id (`tenant_id` / `<parent>_id`), on `status`, and on any column used for routing/lookup queries.
  - RLS enabled with a policy named `hierarchical_visibility`.
  - A `<entity>_is_visible` function (`SECURITY DEFINER`, `STABLE`) that composes with the parent's visibility function — e.g. `conversation_is_visible` calls `agent_is_visible`, which calls `tenant_is_visible`.
  - No explicit grant to the runtime role: the `cauce_app` least-privilege role (wired in `V10`) inherits `SELECT, INSERT, UPDATE, DELETE` on new tables automatically via `ALTER DEFAULT PRIVILEGES`. The role is provisioned outside migrations (docker init locally, Testcontainers `withInitScript` for cauce-api tests).

### 3. Service layer (`cauce-tenancy/`, or a dedicated module if scope justifies it)

- A `@Service` with `@Transactional` methods; `RlsContextAspect` sets the DB tenant context from `TenantContext` before each one runs.
- Validate that referenced parent entities exist via `repository.findById`, relying on RLS for visibility. A not-found result for an entity outside the current `TenantContext` is the correct outcome: do not distinguish "does not exist" from "not visible to you" in the public API, to avoid leaking the existence of out-of-scope entities.
- Validate SPI-bound fields against a temporary hardcoded `Set` with a TODO referencing the future SPI module.
- Operations that create or read on behalf of subordinate tenants (e.g. a partner acting for its client) rely on hierarchical RLS, not strict-owner checks.

### 4. Test layer (three levels)

- Domain unit test (`cauce-core/<Entity>Test.java`): factory behavior, `equals`/`hashCode`, invariant validation.
- Mapper unit test (`cauce-memory/<Entity>MapperTest.java`): round-trip preservation, including nullable fields.
- Service unit test (`cauce-tenancy/<Entity>ServiceTest.java`): validation behavior with mocked repositories.
- Integration test with Testcontainers (`cauce-tenancy/<Entity>ServiceIT.java`): seed the hierarchy via the existing services, verify hierarchical RLS through a dedicated restricted role, check UUIDv7 ordering, and confirm operations succeed across hierarchy levels (operator, partner, client) where appropriate and fail without a `TenantContext`.

### 5. Update existing integration tests

- Add the new table to the `TRUNCATE` statement in the setup of other ITs, since FK relationships make them interdependent.

## Code conventions

### Java

- Package convention: `dev.cauce.<module>.<area>` (e.g., `dev.cauce.core.agent`, `dev.cauce.channels.whatsapp`).
- Use records for immutable DTOs and value objects.
- Use sealed interfaces for closed type hierarchies.
- Use `Optional` for return types that can legitimately be empty. Do not use `null` as a return value.
- Constructor injection only. No `@Autowired` on fields.
- Lombok is permitted only for `@Slf4j` and simple DTOs. Do not use Lombok for business logic.

### Spring Boot

- One `@Configuration` class per module for module-specific beans.
- Use `@ConfigurationProperties` for typed configuration. Avoid `@Value` on individual fields.
- Transaction boundaries belong in the application service layer, not in repositories.
- Repositories extend Spring Data interfaces. Custom queries use `@Query` with named parameters.

### Angular

- Standalone components only. No NgModules.
- Signals for reactive state. RxJS only when truly streaming (HTTP, WebSockets, server-sent events).
- One component per file. Templates and styles co-located unless they exceed reasonable size.
- Tailwind classes for styling. Custom CSS only when Tailwind utilities are insufficient.

### Tests

- Unit tests use JUnit 5, Mockito, and AssertJ. Naming pattern: `methodName_stateUnderTest_expectedBehavior`.
- Integration tests use Testcontainers for PostgreSQL and Redis. Filename suffix `IT.java`.
- Every new public method requires at least one test. Every bug fix requires a regression test.
- Aim for behavior coverage, not line coverage.

## Working with this codebase

### When implementing a feature

1. Identify which module(s) the change belongs to. Default to the smallest scope.
2. Check if the change requires modifying an SPI (channel or LLM). Update interface and reference implementations together.
3. Multi-tenancy must always be considered. Ask: where does the tenant context come from for this operation?
4. Write tests alongside or before the implementation.
5. Update relevant documentation (Javadoc, module README, public docs).

### When fixing a bug

1. Reproduce the bug with a failing test first.
2. Fix the bug.
3. Verify the test passes and no other tests broke.
4. Consider whether other paths could have the same bug.

### When refactoring

1. Ensure all relevant tests pass before starting.
2. Refactor in small, verifiable steps.
3. Run tests after each step.
4. Do not mix refactoring with behavior changes in a single commit.

## Communication protocol for strategic chat

This project uses two AI assistants in parallel:

- **Claude Code** (this tool, in terminal) — executes plans, writes code, 
  runs builds, makes commits within `code/`.
- **Strategic chat** (claude.ai project) — provides architectural decisions, 
  reviews, and high-level guidance from outside the codebase.

After completing any non-trivial task (planning, implementation, verification, 
debugging), append a structured summary block at the end of the response. 
The user copies only this block to the strategic chat, not the full output.

Format (exact, do not deviate):

​```
═══════════════════════════════════════
RESUMEN PARA CHAT ESTRATÉGICO
═══════════════════════════════════════

ESTADO: [plan-pendiente-aprobación | en-ejecución | completado-pendiente-commit | commiteado | fallo]

DECISIONES TOMADAS:
- [Brief list of non-trivial decisions]

PUNTOS DE CRITERIO (requieren validación estratégica):
- [Items that need strategic input, or "ninguno"]

DESVIACIONES DEL PLAN APROBADO:
- [If any, listed, or "ninguna"]

PRÓXIMO PASO PROPUESTO:
[One line with the next step]

═══════════════════════════════════════
​```

Generate this summary block in Spanish. Keep it under 30 lines total. 
Do not include build logs, file listings, or command outputs in the 
summary — that information stays in the full response for local review.

## Build and run

> The backend Gradle build lives under `backend/` — run Gradle from there.
> The Angular frontend under `frontend/` does not exist yet.

### Local development environment

Start the local stack (PostgreSQL + pgvector, Redis, Adminer) from the repo root:

```bash
docker compose up -d         # start services
docker compose ps            # check health
docker compose down          # stop (keeps data)
docker compose down -v       # stop and wipe volumes
```

Host ports: PostgreSQL `5433`, Redis `6379`, Adminer `8081`. Adminer is at
http://localhost:8081 (server `postgres`, database/user/password
`cauce_dev`/`cauce`/`cauce_dev`). Copy `.env.example` to `.env` to override
defaults, and `docker-compose.override.yml.example` to
`docker-compose.override.yml` for local-only tweaks.

The Postgres init scripts under `docker/postgres/init/` (pgvector extension and the
least-privilege `cauce_app` login role) run **only on a fresh volume**. After pulling a
change that adds or edits one, recreate the volume to pick it up:
`docker compose down -v && docker compose up -d`.

### Backend

```bash
cd backend
./gradlew build               # build all modules
./gradlew test                # run unit tests
./gradlew :cauce-api:bootRun  # run the application (dev profile by default)
```

`cauce-api` serves on http://localhost:8080 with the `dev` profile active, which
connects to the Docker Compose services above. Health:
http://localhost:8080/actuator/health.

**Two database roles.** The application runs as the least-privilege `cauce_app` role
(`spring.datasource.*`) so Row-Level Security is enforced at runtime; a privileged owner
role (`cauce.admin.datasource.*`) runs Flyway migrations and the operator bootstrap, which
must bypass RLS. Locally both point at the same database, with `cauce_app` created by the
init script above and granted by migration `V10`. In production, set `DATABASE_*` to the
`cauce_app` credentials and `ADMIN_DATABASE_*` to the owner; the `cauce_app` role must be
provisioned out of band before first start (ops runbook), after which `V10` grants it.
The async worker/reaper run under `cauce_app`: their cross-tenant claim/reap go through the
`V12` SECURITY DEFINER functions, and all processing stays under RLS in the claimed tenant's
context (see `docs/adr/0001-rls-escape-hatches.md`).

**Authentication.** `/v1/**` requires a valid API key (`Authorization: Bearer <key>`); the tenant
context is derived from the validated key, never from a client header. API keys are issued, listed,
and revoked over REST under hierarchical authority (see the inventory below and
`docs/adr/0002-authority-model.md`) — but issuing a key requires authenticating with one, so on the
**first** start against an empty database `OperatorKeyBootstrapRunner` creates the root operator and
logs its API key **once** (`WARN`) — copy it from the log; it cannot be recovered. Subsequent starts
are no-ops. In production this is the documented first-run step; the runner is disabled under the
`test` profile (tests mint their own keys).

### REST surface (v1)

All `/v1/**` endpoints require Bearer API-key auth; JSON is globally snake_case. Out-of-scope
entities surface as 404: "does not exist" and "not visible to you" are deliberately
indistinguishable.

**Pagination (uniform keyset contract).** The three list endpoints below (tenant children,
tenant agents, conversation messages) return the envelope `{"data": [...], "next_cursor":
"<opaque>"|null}` — a **breaking change** from the pre-pagination bare arrays, made while there
are no external consumers. Query params: `limit` (default 50, max 200 — larger values are
clamped, `limit < 1` is a 400 `bad_request`) and `cursor` (opaque, from the previous page's
`next_cursor`; malformed → 400 `invalid_cursor`; `next_cursor: null` is the explicit
last-page terminator). Ordering is keyset on the UUIDv7 `id` (strict total order — uuid-creator's
default factory is monotonic within the same millisecond per JVM, and Postgres compares `uuid`
bytewise), so a full walk never skips or duplicates rows and messages appended mid-walk (a live
conversation) show up at the end. The `id > cursor` comparison always runs in Postgres (Java's
`UUID.compareTo` is signed and disagrees). `GET /v1/tenants/{id}/api-keys` remains an
unpaginated bare array (deferred). The message-list ordering changed from `created_at` to `id`
(equivalent in practice; context assembly still reads `created_at` via `ConversationGateway`).

- **Tenants**: `POST /v1/tenants/partner`, `POST /v1/tenants/client`, `GET /v1/tenants/{id}`,
  `GET /v1/tenants/{id}/children` (paginated)
- **Agents**: `POST /v1/tenants/{tenantId}/agents`, `GET /v1/agents/{id}`,
  `GET /v1/tenants/{tenantId}/agents` (paginated)
- **API keys** (hierarchical authority, ADR 0002): `POST /v1/tenants/{tenantId}/api-keys` (201;
  plaintext key returned exactly once), `GET /v1/tenants/{tenantId}/api-keys` (metadata only),
  `DELETE /v1/api-keys/{keyId}` (204, soft revoke)
- **Messaging**: `POST /v1/agents/{agentId}/messages` (202 Accepted with
  `{conversation_id, message_id, invocation_id}`), `GET /v1/conversations/{id}`,
  `GET /v1/conversations/{id}/messages` (paginated; the visibility probe 404s on every page)
- **Invocations**: `GET /v1/invocations/{id}` — processing status of a queued invocation, for
  polling after the 202. Public vocabulary decoupled from the internal lifecycle: `status` is
  `PENDING | PROCESSING | COMPLETED | FAILED` (internal ABANDONED collapses into FAILED) and
  `failure_reason` (only on permanent failure; null for pre-V17 rows) maps the internal
  `InvocationFailureType` to `PROVIDER_ERROR | PROVIDER_UNAVAILABLE | AGENT_LOOP_LIMIT |
  INTERNAL_ERROR | TIMEOUT`. The row's `last_error` (raw provider detail) is never exposed.
  Rows survive terminal states, so the status stays readable after completion. 404 code:
  `invocation_not_found`.
- **Channels**: `POST /v1/agents/{agentId}/channels` (201; binds a channel instance to the agent,
  `{channel_type, credential}` in, `webhook_secret` returned exactly once — pass it to the
  provider, e.g. Telegram `setWebhook(url, secret_token)`). List/disable deferred.

The messaging endpoint stamps the reserved channel type `api` server-side — the request body
carries only `external_identity_ref` and `content`, so the client cannot choose the channel.
Ingest is atomic (`InboundMessageService`, one transaction): resolve-or-start the OPEN
conversation — race-safe via `INSERT ... ON CONFLICT DO NOTHING` + re-`SELECT`, backed by the V13
partial unique index on `conversations (agent_id, channel_type, external_identity_ref) WHERE
status = 'OPEN'` — then append the USER message and enqueue the async invocation. The agent reply
arrives asynchronously; clients poll the conversation messages, and can poll the invocation
status via `GET /v1/invocations/{invocation_id}`.

The endpoint accepts an optional `Idempotency-Key` header (opaque, ≤255 chars): a repeated POST
with the same key for the same agent replays the original 202 ids and ingests nothing new — no
second USER message, invocation, or `InvocationRequested` event. Deduplication is insert-first
inside the same ingest transaction, on the V15 `ingest_idempotency_records`
`(agent_id, idempotency_key)` unique constraint (RLS-scoped via the agent); matching is by key
only, the body is not fingerprinted. Future `cauce-channels` adapters populate the key with the
provider's message id (the Telegram adapter uses `configId + ":" + update_id`).

**Provider webhooks** live outside `/v1` and outside API-key auth:
`POST /webhooks/channels/{configId}` (SecurityConfig permits `/webhooks/**`). Authentication is
the per-config channel secret verified by the adapter before anything is processed (Telegram:
`X-Telegram-Bot-Api-Secret-Token` vs the stored hash). The endpoint dispatches through the
channel SPI: resolve the ACTIVE config via the V16 SECURITY DEFINER function (the webhook has no
tenant context; the config row carries the owning tenant — ADR 0001), verify, normalize, ingest.
Responses: 200 empty on ingest or on an authentic-but-unsupported update (Telegram redelivers
non-2xx), 401 on a bad secret, 404 on an unknown/disabled config, 400 on a malformed payload.
Local development against real Telegram needs a public URL (tunnel, e.g. cloudflared/ngrok) +
`setWebhook`; the ITs drive the endpoint directly.

### Frontend

> Not present yet. Will be added under `frontend/cauce-dashboard`.

## Deferred / Known gaps

A durable register of work that is consciously deferred. Each item is verified against the code as
of the reconciliation date; this is a backlog record, not a commitment to build these next.
(Last reconciled: 2026-06-10.)

### Large / strategic deferrals

- **Tool-calling / agentic loop — landed.** The "agent vs chatbot" trait is complete across
  sub-units A (neutral tool model in `cauce-core`, executable tool SPI + built-in clock in
  `cauce-tools`, tool-message persistence), B (the `cauce-llm` contract carries the tool model and
  both adapters map it to each provider's wire format), and C (the orchestrator runs the bounded
  dispatch-and-feed-back loop: offer tools → invoke → execute requested tools → feed results back →
  re-invoke, capped at 10 iterations). Remaining deferrals: **per-agent tool scoping** (today all
  registered tools are offered to every agent); a heartbeat to replace the flat 12-minute reaper
  timeout if the loop grows; and counting the tool-definition schema (not just tool-message
  content) against the context window. The only built-in tool today is the `get_current_time` clock.
- **Idempotency of message ingestion — landed** (`Idempotency-Key` header, V15 table, insert-first
  lock in `InboundMessageService`). Remaining deferrals: **no body fingerprint** (replay matches
  by key only; the threat model is byte-identical webhook redelivery, not a client reusing a key
  with a different body) and **no retention purge** for `ingest_idempotency_records` (the table
  has `created_at` + an index so a scheduled `DELETE WHERE created_at < …` is a pure add).
- **Per-tenant LLM credentials.** Only the system-default credential exists
  (`SystemDefaultLlmCredential`, env-var based); there is no per-tenant/BYO-key path. Gates the
  commercial model.
- **Usage accounting — capture landed** (V19 `llm_usage_records`: one immutable row per LLM
  call, attributed to the tenant under RLS, written synchronously by the orchestrator).
  Remaining deferrals: the **query/aggregation surface** (the table is write-only today; the
  endpoint arrives with the dashboard, which defines the aggregations it needs),
  **pricing/cost as a view** over the facts via a separate versioned price table (a stored
  cost would turn a tariff change into retroactive ledger corruption, so it is deliberately
  never materialized on the rows), and per-agent/per-model indexes beyond
  `(tenant_id, created_at)` if the dashboard's aggregations need them.
- **Audit trail — capture skeleton landed** (cauce-governance: `AuditEventRecorder` port +
  V20 `audit_outbox` written in the caller's business tx, V21 append-only `audit_log_entries`
  with UPDATE/DELETE revoked from `cauce_app` by grant, per-tenant drainer assigning the
  contiguous `sequence_number`). Remaining units, in intended order: the **hash chain +
  signature** over the reserved `prev_hash`/`entry_hash`/`signature` columns (per-tenant chain
  computed at drain time; a chain-heads table caching `(last_seq, last_hash)` is an additive
  option); **wiring real auditable events** (the orchestrator loop and tenancy/API-key
  operations call the port inside their txs; defines the real `event_type` vocabulary —
  today's values are semantics-free placeholders); **retention purge of DRAINED outbox rows**
  (the ledger's `outbox_id` deliberately has no FK so the purge is a pure add); and RGPD
  endpoints / policy engine (the module's broader charter).
- **OSS quickstart.** docker-compose runs only PostgreSQL, Redis, and Adminer; there is no
  clone → compose up → agent-responding path (app + Ollama in compose). Adoption surface.
- **Channel delivery guarantee (the "module-after").** Outbound delivery is live but explicitly
  **best-effort**: fire-and-forget on the `channelOutboundExecutor`, no outbox, no retries, no
  delivery dedup. Failures are logged and the reply stays readable by polling. The hook is in
  place — the `AgentReplyDispatcher` port (cauce-core) carries the TODO: persist the delivery
  intent behind the same port and drain it from a scheduled dispatcher, additively.
  **Design decision (recorded)**: the trigger is an explicit port invoked by the orchestrator
  after the final AGENT append commits — NOT an `InvocationCompleted` consumer — so the
  `OrchestrationEvent` stream remains purely observational and the parked AFTER_COMMIT/outbox
  question stays parked. Part of the same module: stamping `channel_config_id` on conversations
  (V17) — today a conversation whose (agent, channel) has ≠1 ACTIVE configs skips delivery with
  a WARN because the originating bot is ambiguous.
- **Channel follow-ups.** Credential (bot token) encryption-at-rest (TODO, consistent with the
  deferred per-tenant LLM credentials); channel-config list/disable endpoints; replacing the
  hardcoded `SUPPORTED_CHANNELS` set in `ConversationService` with SPI-driven validation (needs a
  port — direct dependency is a cycle); WhatsApp adapter (the SPI was designed against it);
  outbound delivery metrics (log-only today; an outbound event would let observability count it).
- **Dashboard.** The frontend does not exist.
- **Observability instrumentation.** Invariant 4 ("observable by default") is partially
  covered: the orchestrator emits invocation lifecycle events (`cauce-orchestration-events`,
  incl. per-call token usage on `LlmResponded`) and `cauce-observability` now consumes them
  into Micrometer metrics (`OrchestrationMetrics`, exposed via the authenticated
  `/actuator/metrics`). Still missing: events outside the invocation path (tenancy/API
  operations emit nothing), distributed traces (no OpenTelemetry dependency), and any metric
  exporter (OTLP/Prometheus) — Micrometer-now/OTLP-later is a bridge, not a migration. When a
  persisting consumer lands, revisit the `AFTER_COMMIT`/outbox TODO in
  `InboundMessageService` (the metrics listener is in-memory only, so it is exempt).

### Minor technical follow-ups

- **No conversation-visible trace for some failures.** The invocation-status gap is closed: the
  202 carries `invocation_id`, `GET /v1/invocations/{id}` reports every terminal state, and the
  V17 `failure_type` column persists the taxonomy (mapped to the public `failure_reason`). What
  remains deferred: reaper-abandoned invocations and non-LLM setup failures still append no
  SYSTEM `[orchestration_error]` message to the conversation (only LLM provider failures and the
  tool-iteration cap do), so their only client-visible signal is the invocation status.
- **Per-model limits beyond the context window.** Only the context window has a registry plus
  conservative fallback (`ModelContextWindow`); max response tokens is a flat 4096 default, never
  per-model.
- **`RESERVED_FOR_RESPONSE`** is a hardcoded 10,000-token constant in `ContextBuilder`; revisit
  when tuning context assembly.
- **`GET /v1/tenants/{id}/api-keys` is still an unpaginated bare array** — the only list
  endpoint outside the uniform keyset contract (key sets per tenant stay tiny; align it when it
  is next touched).
- **`api_keys.last_used_at`** is updated synchronously, but only on the auth cold path (cache hits
  skip the UPDATE; staleness is bounded by the cache TTL). Moving to an async batched update is
  deferred (TODO in `ApiKeyAuthenticationFilter`).
- **Streaming** is not part of the LLM SPI yet — explicitly deferred past v1.0, to be added as a
  separate method. The orchestrator does one blocking `invoke`.
- **Fine-grained authorization.** API keys carry no scopes or roles (empty authorities); deferred
  until a concrete need exists. Authorization today is tenant scoping via RLS only.

## Commit conventions

This project uses Conventional Commits in English.

**Format**: `<type>(<scope>): <description>`

**Types**: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.

**Scope** is the module name (`core`, `memory`, `channels`, `llm`, `evals`, `observability`, `governance`, `tenancy`, `orchestration`, `api`, `enterprise`, `dashboard`) or a cross-cutting area (`deps`, `gradle`, `docker`, `actions`).

**Examples**:
- feat(core): add Agent interface and ConversationState entity
- fix(channels): handle media messages without mime type
- docs(architecture): clarify multi-tier tenancy visibility rules
- refactor(memory): extract vector retrieval into separate service
- build(gradle): upgrade Spring Boot to 3.2.5
- chore(deps): bump Testcontainers to 1.20

Commits are atomic: one logical change per commit. First line ≤72 characters, imperative mood, no trailing period. Bodies are wrapped at 72 characters.

Breaking changes use `!` after the scope and a `BREAKING CHANGE:` footer.

**Attribution**: Commits are attributed exclusively to the human author. Never add `Co-Authored-By` trailers or credit AI tools (Claude Code, IDE assistants, autocomplete) as co-authors, unless the user explicitly requests it for a specific commit.

## Dependencies and updates

Dependency updates are proposed by Dependabot (see `.github/dependabot.yml`): weekly for GitHub Actions, Gradle, and npm.

When Dependabot opens a pull request, evaluate it by version delta:

- **Patch and minor updates**: if CI is green, merge promptly — squash and merge, then delete the branch.
- **Major updates**: do not merge automatically. Close the PR with a comment explaining why the upgrade is deferred, then comment `@dependabot ignore this major version` so Dependabot stops proposing that specific major.

Major versions often carry breaking changes that need manual migration; they are not suitable for unattended updates.

## What not to do

- Do not introduce a feature that bypasses multi-tenancy. Every entity is tenant-scoped.
- Do not introduce a dependency on a specific LLM provider in core. Always go through `cauce-llm` SPI.
- Do not introduce a dependency on a specific channel provider in core. Always go through `cauce-channels` SPI.
- Do not move core-required functionality into `cauce-enterprise`.
- Do not commit secrets, API keys, credentials, or `.env` files.
- Do not use Lombok for business logic. Limit Lombok to logging and simple DTOs.
- Do not skip writing tests for new public methods.
- Do not commit code that fails the build or tests.
- Do not bypass code style or linting.
- Do not commit Unix shell scripts (e.g. `gradlew`, `bin/*.sh`) without the executable bit set in the Git index. This repo is bootstrapped on Windows, which does not preserve Unix exec bits. Set it with `git update-index --chmod=+x <path>` and confirm `git ls-files --stage <path>` reports mode `100755` (not `100644`). `.gitattributes` does not control exec bits — only the index mode does. A missing bit causes "Permission denied" on Linux CI runners.

## License notes

This project is licensed under the Business Source License 1.1. See [LICENSE](LICENSE) for full terms.

The `cauce-enterprise` module is under a separate commercial license. Code in `cauce-enterprise` may depend on BUSL-licensed modules; code in BUSL-licensed modules may never depend on `cauce-enterprise`.

## References

- [README](README.md) — public project overview
- [LICENSE](LICENSE) — Business Source License 1.1
- [GitHub Discussions](https://github.com/cauceos/cauce/discussions) — questions, ideas, partnerships