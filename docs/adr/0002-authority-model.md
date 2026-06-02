# ADR 0002: Hierarchical authority model

Status: Accepted

## Context

Cauce's tenants form a fixed three-level hierarchy (operator → partner → end client). Postgres
Row-Level Security already enforces *visibility*: `tenant_is_visible(row_id, row_parent)` returns
true when the current tenant is the row itself, its direct parent, or its grandparent, and every
table's `hierarchical_visibility` policy is built on it (directly or via a `*_is_visible` helper).

What had not been stated as a decision is the **authority model**: who may *read* and *manage* what.
Two inconsistencies made this concrete:

- Read access was almost uniformly "trust RLS" (`getTenant`, `listChildren`, `listAgentsForTenant`
  all look up by id/parent and let RLS filter), but `AgentService.getAgent` used an *exact-owner*
  filter (`findByIdAndTenantId`), so an ancestor could list a descendant's agents yet not GET one
  by id — an arbitrary asymmetry.
- API-key management moved from a bootstrap-only path to real endpoints (issue/list/revoke), which
  forced the question: under what authority may a tenant mint or revoke a key for *another* tenant?

We needed one rule, applied everywhere, rather than per-endpoint judgement calls.

## Decision

**Authority is hierarchical and IS the RLS visibility relation.** A tenant may read and manage
itself and its descendants — exactly the set `tenant_is_visible` returns true for — and nothing
else. There is no separate authorization layer; access control is the database visibility check.

Concretely:

1. **Reads trust RLS.** Read endpoints look an entity up by id (or by a parent/owner column) and let
   RLS filter; they do **not** add an app-level exact-owner predicate. `getAgent` was realigned to
   `findById` to match (ADR follows the code in commit `feat(tenancy): align getAgent…`).
   Not-found and not-visible are deliberately indistinguishable, so the API never reveals the
   existence of out-of-scope rows.

2. **Management is authorized by visibility of the target.** Minting a key for `{tenantId}` is
   allowed iff that tenant is visible to the caller — enforced by loading it under RLS
   (`createApiKey` → `tenantRepository.findById` → `TenantNotFoundException`/404 when invisible).
   Revoking a key is allowed iff the key is visible (`loadVisible` → `ApiKeyNotFoundException`/404).
   Because the `api_keys` `WITH CHECK` is itself `api_key_is_visible(tenant_id)` (hierarchical), a
   partner inserting a key for its client passes the policy — **no migration was needed**.

3. **Authentication is unchanged and orthogonal.** The acting tenant always comes from the validated
   API key (never a client header); this ADR governs what that authenticated tenant may then do.

4. **No fine-grained authorization yet.** There are no roles or scopes: any valid key for a tenant
   carries the full hierarchical authority of that tenant. Tier rules (e.g. agents only under a
   CLIENT) still constrain shape. Roles/scopes are a future unit.

## Consequences

- One rule, one chokepoint: the RLS `*_is_visible` functions. New endpoints inherit the authority
  model for free by going through RLS-scoped repository calls; they must not reintroduce ad-hoc
  owner filters (that is what made `getAgent` an outlier).
- Self-serve onboarding: a partner provisions its clients with API keys over the API, no operator or
  DB access required.
- No information leak: invisible targets are 404, never 403, so callers cannot enumerate the tree.
- Coarseness is accepted for now: a leaked key grants its tenant's full descendant authority until
  revoked. This is the motivation for a later roles/scopes unit.
- If a genuinely owner-only operation is ever required, it must add an explicit predicate on top of
  RLS and document the exception — the default is hierarchical.

## References

- `backend/cauce-memory/src/main/resources/db/migration/V1__create_tenants_table.sql` — `tenant_is_visible`
- `backend/cauce-memory/src/main/resources/db/migration/V9__create_api_keys_table.sql` — `api_key_is_visible` + the hierarchical `WITH CHECK`
- `backend/cauce-tenancy/src/main/java/dev/cauce/tenancy/ApiKeyService.java` — visibility-authorized mint/list/revoke
- `backend/cauce-tenancy/src/main/java/dev/cauce/tenancy/AgentService.java` — `getAgent` realigned to RLS
- `docs/adr/0001-rls-escape-hatches.md` — the bypass rule that complements this one
