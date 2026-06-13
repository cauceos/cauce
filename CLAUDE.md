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

> **Current state**: backend/ contains 14 Gradle subprojects. Implemented so far: the domain and persistence layers with hierarchical RLS (Flyway migrations V1–V14), tenancy application services, API-key authentication (HMAC-SHA256), an async LLM invocation engine (queue, context assembly, worker/reaper, inbound message ingest), two LLM adapter modules (native Anthropic; OpenAI-compatible covering OpenAI, Mistral, and Ollama), an authenticated REST API including a public messaging endpoint, and the foundation of the tool loop (the neutral tool model in `cauce-core`, the executable tool SPI + built-in clock in `cauce-tools`, and tool-message persistence in `cauce-memory`). `cauce-channels`, `cauce-evals`, `cauce-observability`, `cauce-governance`, and `cauce-enterprise` are empty skeletons. docker-compose.yml provides local PostgreSQL + pgvector + Redis + Adminer for development. The frontend has not been started. Last build at reconciliation (2026-06-10): 480 tests, 0 failures.

**Backend modules** (Gradle subprojects under `backend/`; the Gradle build — `settings.gradle.kts`, wrapper, `gradle/` — lives under `backend/`, not the repo root):

- `cauce-core` — domain model: `Tenant`, `Agent`, `Conversation`, `Message`, `ApiKey` aggregates, the neutral tool model (`ToolDefinition`, and the sealed `ToolContent` = `ToolCall` | `ToolResult`), `MessageRole` (incl. `TOOL_CALL`/`TOOL_RESULT`), `TenantContext`, UUIDv7 generation, API-key hashing ports; no framework dependencies (its only third-party library is uuid-creator)
- `cauce-memory` — persistence: JPA entities, hand-written mappers, Spring Data repositories, `RlsContextAspect`, Flyway migrations (V1–V14, incl. the messages `tool_content` jsonb column). Vector retrieval is planned (pgvector enabled, no code yet)
- `cauce-channels` — channel adapter SPI and reference adapters (WhatsApp, voice, email, web chat) — empty skeleton, not started
- `cauce-llm` — provider-neutral LLM SPI: `LlmProvider`, `LlmProviderRegistry`, credentials, and the neutral invocation model (tool types exist but are not exercised yet). Adapters live in separate modules
- `cauce-llm-anthropic` — native Anthropic adapter (`POST /v1/messages`); its bean is registered only when an Anthropic API key is configured
- `cauce-llm-openai` — single OpenAI-compatible adapter (`POST /chat/completions`) registered as three conditional providers: `ollama` (keyless, dev default), `openai`, `mistral`
- `cauce-tools` — executable tool SPI: the `Tool` contract (`definition()` + `execute(ToolCall)`), a Spring-managed `ToolRegistry` mirroring `LlmProviderRegistry`, and the built-in `get_current_time` clock tool (injectable `java.time.Clock`). Depends only on `cauce-core` (plus spring-context); the neutral tool model lives in core. Global registry; per-agent tool scoping is deferred. Format mapping in the adapters (B) and the orchestrator loop (C) are not built yet
- `cauce-evals` — evaluation framework, conversation testing, regression detection — empty skeleton, not started
- `cauce-observability` — OpenTelemetry integration, traces, metrics, replay — empty skeleton, not started
- `cauce-governance` — immutable audit log, RGPD endpoints, policy engine, AI Act compliance — empty skeleton, not started
- `cauce-tenancy` — application services for tenants, agents, conversations, messages, and API keys; operator bootstrap; HMAC-SHA256 API-key hashing with a Caffeine cache
- `cauce-orchestration` — async LLM invocation engine: pending-invocation queue, context assembly with a per-model context-window registry (`ModelContextWindow`; conservative 16,384-token fallback with a `WARN` for unknown models), orchestrator, background worker/reaper, and `InboundMessageService` (the inbound message ingest unit; depends on `cauce-tenancy`)
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

- **Tenants**: `POST /v1/tenants/partner`, `POST /v1/tenants/client`, `GET /v1/tenants/{id}`,
  `GET /v1/tenants/{id}/children`
- **Agents**: `POST /v1/tenants/{tenantId}/agents`, `GET /v1/agents/{id}`,
  `GET /v1/tenants/{tenantId}/agents`
- **API keys** (hierarchical authority, ADR 0002): `POST /v1/tenants/{tenantId}/api-keys` (201;
  plaintext key returned exactly once), `GET /v1/tenants/{tenantId}/api-keys` (metadata only),
  `DELETE /v1/api-keys/{keyId}` (204, soft revoke)
- **Messaging**: `POST /v1/agents/{agentId}/messages` (202 Accepted with
  `{conversation_id, message_id}`), `GET /v1/conversations/{id}`,
  `GET /v1/conversations/{id}/messages`

The messaging endpoint stamps the reserved channel type `api` server-side — the request body
carries only `external_identity_ref` and `content`, so the client cannot choose the channel.
Ingest is atomic (`InboundMessageService`, one transaction): resolve-or-start the OPEN
conversation — race-safe via `INSERT ... ON CONFLICT DO NOTHING` + re-`SELECT`, backed by the V13
partial unique index on `conversations (agent_id, channel_type, external_identity_ref) WHERE
status = 'OPEN'` — then append the USER message and enqueue the async invocation. The agent reply
arrives asynchronously; clients poll the conversation messages.

### Frontend

> Not present yet. Will be added under `frontend/cauce-dashboard`.

## Deferred / Known gaps

A durable register of work that is consciously deferred. Each item is verified against the code as
of the reconciliation date; this is a backlog record, not a commitment to build these next.
(Last reconciled: 2026-06-10.)

### Large / strategic deferrals

- **Tool-calling / agentic loop.** The foundation (sub-unit A) has landed: the neutral tool model
  lives in `cauce-core`, the executable tool SPI + registry + built-in clock in `cauce-tools`, and
  tool messages (`TOOL_CALL`/`TOOL_RESULT`) persist with their structured payload in the messages
  `tool_content` jsonb column. Still missing: the LLM adapters do not yet map tools to/from each
  provider's wire format (sub-unit B — `LlmInvocation.tools` is still sent empty and
  `LlmResponse.toolCalls` is never read; `cauce-llm` keeps its own skeletal `ToolDefinition`/
  `ToolCall` pending migration to the core model), and the orchestrator still performs single-step
  invocation rather than the dispatch-and-feed-back loop (sub-unit C; `ContextBuilder` deliberately
  rejects tool roles until then). This is the "agent vs chatbot" trait.
- **Idempotency of message ingestion.** `POST /v1/agents/{agentId}/messages` has no idempotency
  key: a client retry or webhook redelivery after a committed ingest duplicates the USER message
  and its invocation. Prerequisite for real channels (at-least-once webhook delivery); pair with
  `cauce-channels`.
- **Per-tenant LLM credentials and usage accounting.** Only the system-default credential exists
  (`SystemDefaultLlmCredential`, env-var based); there is no per-tenant/BYO-key path. Token usage
  (`LlmUsage`) is logged but never persisted or attributed per tenant. Both gate the commercial
  model and billing.
- **OSS quickstart.** docker-compose runs only PostgreSQL, Redis, and Adminer; there is no
  clone → compose up → agent-responding path (app + Ollama in compose). Adoption surface.
- **Real channels and dashboard.** `cauce-channels` is an empty skeleton (not even the SPI exists
  yet) and the frontend does not exist.
- **Observability instrumentation.** Invariant 4 ("observable by default") is not implemented yet:
  no structured events, traces, or metrics are emitted anywhere; there is no OpenTelemetry or
  Micrometer dependency, and `cauce-observability` is an empty skeleton.

### Minor technical follow-ups

- **No failure signal for permanently failed invocations.** LLM provider failures do surface as
  SYSTEM `[orchestration_error]` messages in the conversation, but reaper-abandoned invocations and
  non-LLM setup failures leave no conversation-visible trace, and there is no invocation-status
  endpoint (the public 202 response drops the invocation id).
- **Per-model limits beyond the context window.** Only the context window has a registry plus
  conservative fallback (`ModelContextWindow`); max response tokens is a flat 4096 default, never
  per-model.
- **`RESERVED_FOR_RESPONSE`** is a hardcoded 10,000-token constant in `ContextBuilder`; revisit
  when tuning context assembly.
- **Pagination** is deferred on all list endpoints (`GET /v1/tenants/{id}/agents`,
  `GET /v1/tenants/{id}/children`, `GET /v1/conversations/{id}/messages`) — explicit TODOs in the
  controllers.
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