# ADR 0001: RLS escape hatches

Status: Accepted

## Context

Cauce is multi-tenant from the first commit, with tenant isolation enforced at the database
layer by PostgreSQL Row-Level Security. At runtime the application connects as the
least-privilege role `cauce_app` (NOSUPERUSER, NOBYPASSRLS, not a table owner), so every query
is filtered by the hierarchical_visibility policies. `RlsContextAspect` sets
`app.current_tenant_id` from the thread-local `TenantContext` before each `@Transactional`
`@Service` method, and the policies fail-closed when no context is set.

Most work fits this model: a request (or a per-tenant task) sets the context and runs under RLS.
But a few operations are legitimately **cross-tenant** or **out-of-tenant** and cannot set a
single tenant context:

- **API-key authentication** must look up a key by prefix *before* it knows which tenant owns
  it (the lookup is what discovers the tenant).
- **The async worker/reaper** drain a queue (`pending_invocations`) across all tenants: the
  claim and the orphan scan have no single owning tenant.
- **Schema migrations and the first-operator bootstrap** run outside any tenant entirely.

Under `cauce_app` these all fail-closed. We need a disciplined way to grant exactly the
cross-tenant reach each one needs — and no more — rather than handing the runtime a broad
bypass (a `BYPASSRLS` role or owner rights), which would dissolve the isolation guarantee for
every query the app makes.

## Decision

There are exactly **three** sanctioned patterns. Everything else runs RLS-scoped as `cauce_app`.

1. **RLS-scoped context (the default).** A per-request / per-tenant entry point sets
   `app.current_tenant_id` and runs as `cauce_app` under RLS. This is how virtually all
   application code works; the other two patterns are exceptions that must be justified.

2. **Narrow `SECURITY DEFINER` function** — for the unavoidable cross-tenant lookups/claims.
   - Owner-owned, so it bypasses RLS (tables are `ENABLE`, not `FORCE`, row-level security).
   - Does the **minimal** operation and returns the **minimal** columns, always including
     `tenant_id`.
   - `SET search_path = pg_catalog, public` (pinned, against search-path injection).
   - `EXECUTE` revoked from `PUBLIC` and granted only to `cauce_app`.
   - The caller takes the returned `tenant_id`, sets `TenantContext` to it, and does **all**
     remaining work under RLS. The bypass is confined to the lookup/claim itself.
   - Examples: `api_keys_active_by_prefix` (V11); `claim_pending_invocations` and
     `orphaned_invocations` (V12). For the worker, the claim performs the
     PENDING→PROCESSING transition atomically in SQL (it must be atomic under
     `FOR UPDATE SKIP LOCKED` to prevent double-claim); reap only *discovers* orphans and the
     recovery transition runs in the domain under RLS.

3. **Owner (admin) datasource** — for out-of-tenant administrative operations only:
   Flyway migrations and the first-operator bootstrap (`OperatorBootstrap`, on
   `cauce.admin.datasource`). Never used for per-request work.

### The rule

No runtime role has `BYPASSRLS` or owner/superuser rights. Cross-tenant *runtime* needs go
through a narrow `SECURITY DEFINER` function — never a broad bypass role. Confine each bypass
to the smallest possible surface, return the minimum, and have the caller re-establish a tenant
context and finish under RLS. Out-of-tenant *administrative* needs (migrations, bootstrap) use
the owner connection, never the request path.

## Consequences

- Tenant isolation holds for the entire application surface; the only cross-tenant reach is a
  small, auditable set of owner-owned functions whose `EXECUTE` is restricted to `cauce_app`.
- Each new cross-tenant need is a deliberate, reviewable act: add a narrow function and document
  why, rather than widening a role.
- A little more ceremony: a cross-tenant query is a migration (the function) plus a repository
  binding, not a plain query. This friction is intended — it keeps bypasses rare and explicit.
- The `@NoTenantContext` marker remains the in-process signal that a method runs without a
  tenant context; what changed is the DB-level mechanism behind it (a SECURITY DEFINER function
  or the owner datasource), never a bypass role.

## References

- `backend/cauce-memory/src/main/resources/db/migration/V11__api_key_prefix_lookup_function.sql`
- `backend/cauce-memory/src/main/resources/db/migration/V12__pending_invocation_claim_reap_functions.sql`
- `backend/cauce-tenancy/src/main/java/dev/cauce/tenancy/OperatorBootstrap.java`
- `backend/cauce-api/src/main/java/dev/cauce/api/DataSourceConfig.java` (the cauce_app / owner split)
- `backend/cauce-core/src/main/java/dev/cauce/core/tenant/NoTenantContext.java`
