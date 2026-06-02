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
- Redis for cache and ephemeral state
- Spring Application Events for internal eventing
- OpenTelemetry for observability
- JUnit 5, Mockito, AssertJ, Testcontainers for testing

**Frontend**
- Angular 17+ with standalone components and Signals
- TypeScript 5+ (strict mode)
- Tailwind CSS
- Angular CLI for build and dev server
- Jasmine + Karma for unit tests, Playwright for e2e

**Infrastructure**
- Docker + Docker Compose for development
- Helm chart for Kubernetes production deployments
- GitHub Actions for CI/CD

## Repository structure

> **Current state**: foundational structure in place. backend/ contains the Gradle multi-module skeleton with 10 subprojects compiling cleanly but without business logic yet. docker-compose.yml provides local PostgreSQL + pgvector + Redis + Adminer for development. Domain code and frontend not yet started.

**Backend modules** (Gradle subprojects under `backend/`):

- `cauce-core` — domain model, agent runtime, conversation state, plugin SPI
- `cauce-memory` — persistent state, cross-channel identity, vector retrieval
- `cauce-channels` — channel adapter SPI and reference adapters (WhatsApp, voice, email, web chat)
- `cauce-llm` — LLM provider SPI and reference adapters (OpenAI, Anthropic, Mistral, Ollama)
- `cauce-evals` — evaluation framework, conversation testing, regression detection
- `cauce-observability` — OpenTelemetry integration, traces, metrics, replay
- `cauce-governance` — immutable audit log, RGPD endpoints, policy engine, AI Act compliance
- `cauce-tenancy` — multi-tenant isolation, hierarchical visibility, quotas, usage tracking
- `cauce-api` — REST API surface; the application module that runs Spring Boot
- `cauce-enterprise` — commercial modules under separate license

**Frontend** (Angular project under `frontend/`):

- `cauce-dashboard` — operator interface for managing workspaces, agents, conversations, costs

**Other top-level directories**:

- `.github/` — GitHub workflows, assets, issue templates
- `docs/` — public documentation (populated as project matures)

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

When introducing a new domain entity that participates in the tenant hierarchy, follow this pattern (proven across Tenant, Agent, and Conversation). It keeps the hexagonal boundaries clean and makes tenant isolation enforceable at the database layer.

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
context is derived from the validated key, never from a client header. There is no key-issuance
endpoint yet, so on the **first** start against an empty database `OperatorKeyBootstrapRunner`
creates the root operator and logs its API key **once** (`WARN`) — copy it from the log; it cannot
be recovered. Subsequent starts are no-ops. In production this is the documented first-run step;
the runner is disabled under the `test` profile (tests mint their own keys).

### Frontend

> Not present yet. Will be added under `frontend/cauce-dashboard`.

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