# ADR 0014: A web-chat channel for online shops

Status: **Proposed.** Records the decisions taken in the strategic chat on 2026-11-09. The ADR is
completed, and accepted, in unit W0, after R2 (ADR 0013). Nothing is built.

## Context

Online shops (static sites, for example Astro on Cloudflare) want the agent embedded in their
own pages. The existing channels do not cover this:

- **The channel SPI expects a provider that calls Cauce.** `InboundChannelAdapter` verifies and
  parses a provider's webhook. A browser widget is a client of Cauce's own, with a session,
  cross-origin requests and reads of its own replies.
- **No API key may live in a browser.** `/v1/**` keys carry tenant authority (ADR 0002). Any key
  embedded in a public page is public.
- **The API has no CORS** (`docs/deferred.md`, API), and nothing in Cauce limits request rates.
  Redis is wired but unused.
- **An anonymous public endpoint spends the tenant's money.** The spend limit (ADR 0010) is
  optional today.
- **`SESSION_ID` already exists** in `IdentityKind` and in the CHECK of `V25` ("a web-chat
  session", ADR 0004). No new kind is needed. What this ADR decides is its meaning.
- **The shop's questions need tools the bar's do not:** stock, and the state of an order.

## Decisions taken (2026-11-09)

### WC1 — A public key per agent, never an API key

A channel config of type `web` carries a **public key** (`pk_…`). It is not secret and is not an
`ApiKey`. It allows exactly three things: open a session, send a message to its agent, and read
that session's own messages. The config also holds the allowed origins and the sealed Turnstile
secret. The tenant is resolved from the key through a `SECURITY DEFINER` function (ADR 0001), and
ingest then runs under RLS. The surface lives outside `/v1` and outside API-key auth. CORS is
checked **per config**, against its allowed origins, never as a global property.

### WC2 — An anonymous identity per browser session

Opening a session needs a valid Cloudflare Turnstile token. Cauce mints a random session token,
stores only its hash, and returns it to the widget. The identity is `SESSION_ID` with the
session's id as its value, never the token.

- **Lifetime: 30 days, sliding**, renewed by use. **Independent of R2** (ADR 0013): R2 closes the
  thread after its window, while the session, and so the identity, survives. A person who comes
  back within 30 days is the same identity in a new thread. Their bookings, cases and orders are
  read by tool.
- **The token lives in the shop page's `localStorage`.** This is deliberate and documented. It
  is the shop's origin, not Cauce's, and it holds only an anonymous session's capability. The
  playground's memory-only rule is about tenant API keys and does not apply here.

### WC3 — Abuse

- Rate limits **per session and per IP**, in Redis. The IP is read from `CF-Connecting-IP` only
  when the proxy is declared trusted.
- Turnstile on opening a session (WC2).
- **A spend limit is mandatory for a tenant with a web channel.** Creating a `web` config without
  one is refused, and removing the limit while a `web` config is active is refused. The limit is
  set from above (ADR 0010), so the client's partner or operator must set it first.
- Bounds on message size and on messages per conversation (values in W0).

### WC4 — Delivery: polling in v1, SSE later

v1 reads replies by polling the session's messages after a `seq` cursor, the same model as the
`api` channel. This works unchanged with the asynchronous orchestrator. SSE comes later, signalled
after commit from the `AgentReplyDispatcher`. With more than one instance it needs a fan-out
(Redis pub/sub or `LISTEN/NOTIFY`), to be decided then.

### WC5 — The AI notice and privacy

- **The AI notice is fixed.** The widget always says the person is talking to an AI (AI Act,
  art. 50). It cannot be configured away.
- **Privacy:** by default, an informational notice with a link to the shop's policy. **Explicit
  acceptance before the first message can be turned on per channel.** Which one applies is the
  data controller's choice (the shop), not Cauce's. Cauce provides both.

### WC6 — Shop tools

- **Stock is its own method on the catalog port** (`CatalogSource`), not a catalog attribute.
  Stock changes far more often than the catalog, and a POS or e-commerce adapter answers it on its
  own.
- **Order status is a new port in `cauce-core`**, with a native adapter and no external system
  integrated yet. An anonymous person asks with the **order number plus the email** on the order,
  with **a limit of failed attempts per session and per IP**, so that order numbers cannot be
  enumerated and a stranger's order is never shown.

### WC7 — The harness

A shop sector pack under `examples/` with its reference data and scenarios: catalog, stock, an
order verified by number and email, a stranger's order refused, a prompt-injection attempt, and
the AI notice. The harness's run stops assuming the bar's fixture (`bar-ejemplo`, `carta.json`).

## To be completed in W0

- The public surface's endpoints and payloads, and the widget's embedding contract (one
  `<script>` with the public key).
- The values of the limits (WC3) and of the failed-attempt limit (WC6).
- The units W1–W7 and their order.

## References

- `docs/adr/0001-rls-escape-hatches.md`: resolving a tenant without context
- `docs/adr/0002-authority-model.md`: who sets the spend limit
- `docs/adr/0004-identity-message-parts-and-channel-capabilities.md`: `SESSION_ID`
- `docs/adr/0010-usage-prices-and-spend-limits.md`: the spend limit
- `docs/adr/0013-new-thread-after-inactivity.md`: R2
