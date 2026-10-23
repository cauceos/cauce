# ADR 0010: Usage, prices and spend limits per tenant

Status: Accepted.

## Context

Every call to a model writes one row to `llm_usage_records` (V19, V43): tokens in and out, the parts
of the prompt read from or written to the provider's cache, provider, model, agent, conversation and
invocation. The design rules already settled that cost is never a stored column: usage is a fact,
price is a versioned table, and cost a view over both. Until now nothing aggregated the ledger,
there was no price table, and nothing kept a tenant from spending without bound.

A partner running bars for its clients needs three things: to see what each client consumes, by day
and by month, in tokens and in money; to cap what a client may spend in a month; and, when the cap
is reached, a customer who still gets an answer and an owner who is told — never silence.

The eval harness already estimates cost from the same ledger (ADR 0009, E10), with its own price
file `precios.yaml`.

## Decision

### S1 — Prices: an operator's table, added over the API

`llm_prices` holds the provider's list price of a model, in US$ per million tokens: input, output,
and, optionally, cache read and cache write (absent, they are priced as input). A row is never
changed: a new price is a new row, in force from the moment the database stamps it (`valid_from =
now()`), so a tariff change never reprices past calls. No migration carries prices: they are data of
the deployment, not schema, and a price in a migration would make a tariff a release. The demo
script adds the price of the model it uses.

The price is the provider's cost, in US$ (no other currency, no partner margin). Rows belong to the
operator tenant; only an operator adds them (`POST /v1/llm-prices`, `admin.llm_price.added` in the
operator's chain). Every tenant can read the prices of its ancestors, so the cost of a client's
usage can be computed under the client's own row-level security; nobody can read prices of a
tenant that is not itself, an ancestor or a descendant.

The harness keeps `precios.yaml`: it estimates a run before the application exists and must stay
hermetic (ADR 0009, E1). The two tables hold the same kind of fact for different readers.

### S2 — Cost: a view, never a column

`llm_usage_costs` is `llm_usage_records` plus `cost_usd`: each call priced with the row of its
provider and model in force at its `created_at`. The view is `security_invoker`, so the RLS of the
ledger applies to whoever reads it. A call with no price in force has a null cost: it counts as an
unpriced call, never as zero.

`created_at` is stamped by the database clock (ADR 0008): the day and month a call is billed to,
and the spend limit, are judged on it.

### S3 — The usage endpoint

`GET /v1/tenants/{id}/usage` aggregates the usage of `{id}` and its descendants, per day or per
month (`granularity`), per tenant or per agent (`group_by`), over a range of dates (`from`, `to`,
both inclusive, at most 366 days; by default the current month). Days and months are cut in
`time_zone`, UTC by default, so that a month of the endpoint is the month of the limit. Each row
carries the four token counts, the number of calls, the number of conversations, the cost, and the
unpriced calls. A conversation is counted in a period when the model was called in it during that
period.

Visibility is the RLS relation of ADR 0002: a partner sees its clients, a client sees itself, and a
sibling is a 404. The response is bounded by the range, not paginated: it is the one aggregate
response outside the keyset contract.

### S4 — Auditing a read

A read is audited (`admin.usage.read`, in the chain of the tenant read, with the acting tenant as
actor) only when the acting tenant is not the tenant read: what is recorded is that someone looked
at another tenant's consumption. A tenant reading its own usage is not a fact for its chain.

### S5 — The monthly spend limit: a client's, set from above

A limit is set on CLIENT tenants only, in US$ per calendar month. Only a strict ancestor sets or
removes it (`PUT` and `DELETE /v1/tenants/{id}/spend-limit`, `admin.spend_limit.changed`); the client
reads it but cannot raise it. **This departs from ADR 0002**, where authority is the visibility
relation and a tenant manages itself: a limit a client could lift would not limit anything. The
departure is enforced twice: by the service, and by the RLS policy of `tenant_spend_limits`, whose
writes require the row's tenant to be visible and different from the current one.

### S6 — Enforcement

The month is the calendar month in UTC. Before each invocation, the orchestrator compares what the
client spent this month with its limit:

- **At 80 %**, the owner is warned; the invocation runs.
- **At 100 %**, the invocation fails as `SPEND_LIMIT_REACHED` (public reason
  `SPEND_LIMIT_REACHED`): the customer gets the agent's fallback reply (ADR 0005, C3) and the owner
  is told. The customer's message is kept, as for any other failure.

Each threshold is reported once per tenant and month: `spend_alerts` is unique per tenant, month and
threshold, and only the insert that creates the row notifies the owner (an owner notice,
`SPEND_WARNING` or `SPEND_LIMIT_REACHED`, through the `SpendAlertNotifier` port that cauce-business
implements) and records `conduct.spend.threshold_reached` in the client's chain. When the owner has
no linked channel the notice is recorded `NOT_SENT` with its reason, as every owner notice is.

The check runs once per invocation, not per round: an invocation that started is not cut halfway
through its tools. The limit is therefore soft: invocations of one client running at the same time
can each pass the check, and the overrun is bounded by their number times the cost of one
invocation.

### S7 — A model without a price

Without a limit, a call to a model with no price runs and is reported as unpriced. With a limit, an
invocation of an agent whose model has no price in force fails as `AGENT_CONFIGURATION` (public
reason `AGENT_MISCONFIGURED`): a limit that cannot price what it limits would be no limit.

## Consequences

- Cost is always derivable and never stale; a price added late prices only what comes after it.
- The usage query and the spend check scan the ledger on the fly over `(tenant_id, created_at)`.
  For one bar this is a few thousand rows a month; the RLS function runs once per row, so a partner
  with many clients is the case to measure before adding roll-ups. *Measured on 2026-10-23 (100
  clients, 400,000 calls): 10-30 s for the partner's month, 250 ms for a client's. V47 made the
  ledger's policy set based and the view join prices by validity range: 2 s and 9 ms.*
- A blocked client's customers are answered and its owner is told; the partner sees the state of
  the limit over the API.
- Margins, other currencies and limits on partners are not built; a limit on a partner would need to
  sum usage across clients the worker cannot see, a SECURITY DEFINER function (ADR 0001).

## Amendment of 2026-10-23: two cache retentions

Measuring the bar's cost showed that most of each call is a prefix shared by every customer (the
tools and the prompt, about 4,900 tokens) and that, with the 5-minute cache, a person who answers
after five minutes makes the whole history be written again. The provider also offers a cache that
lives an hour, whose writes cost more (2 × input against 1.25 ×). Decided: build it.

**S8 — Long-retention writes are their own fact.** `llm_usage_records.cache_write_long_tokens` (V49)
is the part of the cache writes made for the long retention, read from the provider's breakdown of
its writes by lifetime; `llm_prices.cache_write_long_usd_per_mtok` prices them, and the cost view
charges short and long writes each at its own price. Absent, a long write is priced as input, the
rule of the other cache prices. The usage endpoint and the invocation's usage report it, and the
eval harness records it per run and per scenario (`cache_escritura_larga`).

## References

- ADR 0001 — RLS escape hatches
- ADR 0002 — Hierarchical authority model (S5 departs from it)
- ADR 0005 — Agentic loop hardening (C3, the fallback reply)
- ADR 0007 — Business capabilities (B8, B9, owner notices)
- ADR 0008 — One time source: the database clock
- ADR 0009 — The eval harness (E10, cost)
