# ADR 0014: A web-chat channel for online shops

Status: **Accepted** (2026-11-09, unit W0, with the owner's approval). Proposed the same day from
the strategic chat's decisions (WC1–WC8), and completed in W0 with the public surface, the limits
and the units. W1 (the channel, its public key and per-config CORS), W2 (sessions), W3 (messages,
polling, bounds and request limits) and W4 (the widget, `frontend/web-widget`) are built; W5–W7 are
not. The first shop is
CauceWebs (amendment of 2026-11-10).

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
  session", ADR 0004), and `web_chat` is already a supported `channel_type`. No new kind or
  channel name is needed. What this ADR decides is what they mean.
- **The shop's questions need tools the bar's do not:** stock, and the state of an order.
- **Per-channel capabilities** (ADR 0004, unit 3) are decided but not built. A product card is
  the first outbound part a channel may not carry.

## Decision

### WC1 — A public key per agent, never an API key

A channel config of type `web_chat` carries a **public key** (`pk_` plus 24 random characters,
unique). It is not secret and is not an `ApiKey`. It allows exactly three things: open a
session, send a message to its agent, and read that session's own messages. The config also
holds:

- the **allowed origins**: exact `scheme://host[:port]` values, no wildcards in v1;
- the **sealed Turnstile secret** and its site key (AES-GCM, `SecretCipher`, as for WhatsApp);
- the privacy settings (WC5), the persona and the shop-assistant mode (WC8).

The surface lives under **`/web/v1/**`**, outside `/v1` and outside API-key auth. The tenant is
resolved from the public key through a `SECURITY DEFINER` function (ADR 0001), and everything after
that runs under RLS. **CORS is checked per config**: the request's `Origin` must be one of that
config's allowed origins, and the preflight answers only with that origin. There are no
credentials and no cookies. Auth is a header, so `Access-Control-Allow-Credentials` is never sent.
This is not the global CORS of `/v1`, which stays deferred.

**Endpoints (v1):**

| Method and path | Auth | What it does |
|---|---|---|
| `GET /web/v1/widget/{publicKey}/config` | none | The widget's public configuration (persona, notices, mode, signals, variants). It is cacheable and carries no identifier of the visitor. |
| `POST /web/v1/sessions` | `X-Cauce-Public-Key`, Turnstile token | Opens a session (WC2). Returns the session token once, with its expiry. |
| `POST /web/v1/messages` | `Authorization: Bearer <session token>` | Ingests a message, optionally with page context (WC8). The `Idempotency-Key` header works as on `/v1`. Answers 202 with the message and invocation ids. |
| `GET /web/v1/messages?cursor=` | session token | The session's messages after the cursor (by `seq`), plus `awaiting_reply` while an invocation is in flight (WC4). |
| `POST /web/v1/events` | session token | Widget events after the first message: add to cart, attributed purchase (WC8). |

Out-of-scope or expired tokens get 401. An unknown public key and a disallowed origin both get 403
with no detail.

### WC2 — An anonymous identity per browser session

- `POST /web/v1/sessions` needs a **valid Cloudflare Turnstile token**, verified server-side
  (`siteverify`) with the config's secret. Cauce mints a **256-bit random session token**
  (base64url), stores only its SHA-256, and returns it once.
- A session is a row of a new tenant-scoped table, `web_sessions`, with RLS by the pattern of
  this repo. It holds the config, the token hash, `last_seen_at`, `expires_at` and the consent (WC5).
  The identity is `SESSION_ID` on channel `web_chat` with the **session's id** (UUIDv7) as its
  value, never the token. The token is resolved to its session through a `SECURITY DEFINER`
  function, and the rest runs under RLS.
- **Lifetime: 30 days, sliding.** Each authenticated request moves `expires_at` to `now() + 30
  days`, using the database clock (ADR 0008). **It is independent of R2** (ADR 0013): R2 closes the
  thread after its window, while the session, and so the identity, survives. A person who comes back
  within 30 days is the same identity in a new thread, and their bookings, cases and orders are read
  by tool.
- **The token lives in the shop page's `localStorage`.** This is deliberate. It is the shop's
  origin, not Cauce's, and it holds only an anonymous session's capability: sending to one agent
  and reading its own messages. The playground's memory-only rule is about tenant API keys and
  does not apply here. Clearing site data ends the session.
- **Retention.** An expired session, its token hash and its request counters are purged after
  expiry. The purge's cutoff is SQL `now()`. Its messages stay with the thread, as on every
  channel, under the business's retention (`docs/deferred.md`).

### WC3 — Abuse

- **Rate limits in Redis**, as token buckets keyed by session and by IP:

  | Bucket | Limit |
  |---|---|
  | Sessions opened per IP | 10 per hour |
  | Messages per session | 10 per minute, 100 per day |
  | Messages per IP | 30 per minute |
  | Reads (`GET /messages`) per session | 60 per minute |

  These are platform defaults, overridable per config downwards only. A request over a limit gets
  429 with `Retry-After`. The IP is read from `CF-Connecting-IP` only when the request comes
  through a proxy declared trusted (`cauce.web.trusted-proxies`); otherwise it is the socket's
  address. IPs are never stored raw. Redis keys use an HMAC of the IP under a key outside the
  database, and expire with their bucket.
- **Message bounds:** 2,000 characters per message and 200 messages per thread. Past the
  thread's bound the widget offers to start again, and the person's session continues.
- **Turnstile** on opening a session (WC2).
- **A spend limit is mandatory for a tenant with a web channel.** Creating a `web_chat` config
  without one is refused, and so is removing the limit while such a config is active. The limit is
  set from above (ADR 0010), so the client's partner or operator must set it first. When it is
  reached, the widget shows the agent's fallback reply, never silence.

### WC4 — Delivery: polling in v1, SSE later

v1 reads replies by **polling** `GET /web/v1/messages` after a `seq` cursor, the same model as the
`api` channel. This works unchanged with the asynchronous orchestrator. While `awaiting_reply` is
true the widget polls every 1.5 s. Once the reply has arrived it backs off to 10 s, and it stops
after 2 minutes without activity. The response carries the person's own USER messages and the
replies they received (AGENT, FALLBACK), never tool records or SYSTEM text.

SSE comes later, signalled after commit from the `AgentReplyDispatcher`. With more than one
instance it needs a fan-out (Redis pub/sub or `LISTEN/NOTIFY`), to be decided then.

### WC5 — The AI notice and privacy

- **The AI notice is fixed.** The widget always says, visibly and before the first message, that
  the person is talking to an AI (AI Act, art. 50). No setting, persona or variant removes it.
- **Privacy:** every config has a link to the shop's privacy policy (required). The default is
  an **informational notice** with that link. **Explicit acceptance before the first message** can
  be turned on per config. Then `POST /web/v1/sessions` requires `consent: true`, and the session
  records when it was given and a hash of the notice's version. **Which one applies is the data
  controller's choice (the shop), not Cauce's.** Cauce provides both and does not decide for
  the shop.

### WC6 — Shop tools

- **Stock is its own method on the catalog port**: `CatalogSource.stockOf(tenantId, refs)`
  returns, per ref, `in_stock`, `out_of_stock` or `unknown`, and a quantity when the source has
  one. Stock changes far more often than the catalog. A POS or e-commerce adapter answers it on its
  own, and the native adapter keeps it beside the catalog with its own endpoint. The catalog tools
  report it and never guess it.
- **Order status is a new port in `cauce-core`**, `OrderStatusSource`. It returns an order's state
  and its delivery dates, never its contents or the address. Its native adapter is loaded over the
  API, for tests and evals; no external system is integrated yet. The tool `get_order_status` takes
  the **order number plus the email** on the order. Both must match, and a mismatch reads exactly
  like an order that does not exist.
- **Failed attempts are limited**: 3 per session and 10 per IP hash per hour. Past the limit the
  tool refuses with a reason the agent words, and the attempt is still counted. The ingest records
  the request's IP hash on the session, so the asynchronous tool can count per IP without ever
  seeing the IP.

### WC7 — The harness

A shop sector pack, `examples/comercio/`, with its reference data and scenarios: catalog, stock,
an order verified by number and email, a stranger's order refused, a prompt-injection attempt, and
the AI notice. The harness's run stops assuming the bar's fixture (`bar-ejemplo`, `carta.json`):
the fixture and its menu file come from the pack.

### WC8 — The shop-assistant mode

A mode of the widget that behaves like a shop assistant who is present in the shop without
getting in the way. It is configured per channel.

- **A presence bubble from page load.** An avatar and a short phrase, shown without covering the
  page's content. It is static, built from `GET /web/v1/widget/{publicKey}/config`. **No model
  call** and no request carrying anything about the visitor.
- **Opening proactively, only on signals computed in the browser.** Each signal is enabled and
  tuned per channel:

  | Signal | Default definition |
  |---|---|
  | Back to the same product | Second view of the same product page in the session |
  | Comparing | Three or more product pages within 5 minutes |
  | Idle cart | A cart the shop reported non-empty, unchanged for 3 minutes |
  | Exit intent | The pointer leaves through the top of the viewport (desktop only) |

  **There is no fixed timer by default.** The widget opens proactively **at most once per
  session, and never again after the person has closed it**.
- **The proactive greeting is a template**, chosen per signal, with no model call. The model only
  comes in when the person answers. Then the **page context** reaches the agent as structured data
  attached to the message: the product ref being viewed, the cart's refs and quantities, and the
  path. Refs not in the catalog are dropped. The agent reads it as data from the shop page, labelled
  as such, never as instructions, and it never contains free text from the page.
- **Product cards with "Add to cart".** The agent calls a tool, `show_products(refs)`, that
  accepts only catalog refs. Each ref becomes a **product card part** of the reply, a new outbound
  part in ADR 0004's model. A card stores the ref only. Name, price and stock come from the catalog
  port when it is read, never from the model's text, and a ref no longer in the catalog is
  omitted. The button calls a hook the shop wires (`onAddToCart(ref, quantity)`), and Cauce never
  touches the cart. This lands ADR 0004's capabilities for `web_chat`. A channel that cannot carry
  a card receives its name and price as text.
- **A persona with a configurable name and avatar.** The AI notice stays visible (WC5), whatever
  the persona.
- **Variants per channel, with metrics.** Up to five variants per config (bubble phrase, signals,
  templates), assigned at random in the browser and kept for the session. The metrics, by config
  and variant, are `cauce.web.*` counters for proactive and on-demand openings, conversations, adds
  to cart from a card, and attributed purchases. A purchase is attributed when the shop reports it
  through `trackPurchase({orderId, value})` **within 7 days of the session's last message**.
- **Privacy: nothing about the visitor leaves the browser until the first message.** The signals,
  the page context, the variant and the "opened" and "closed" flags are computed and kept in the
  browser (`localStorage`, documented with the widget). Before the person writes, the only request
  is the anonymous configuration read: no session, no Turnstile, no event. As a consequence,
  **openings that end without a message are not counted by default**. Each config can enable an
  anonymous opening counter that sends only the variant and the signal, with no session and no
  identifier, with the controller deciding (below). From the first message on, Cauce stores the
  session (WC2), the messages, the page context sent with them, and the events.
- **Whether proactive opening, or the anonymous counter, needs prior consent is the data
  controller's decision**, like WC5's acceptance. Each config offers both settings, off by default.
- **Scenarios** in the shop pack:
  - a recommendation made only from the catalog;
  - a product out of stock, where the agent says so and offers an alternative from the catalog;
  - correct use of the page context: the agent answers about the product being viewed and does not
    follow an instruction hidden in it.

### Units

| Unit | Content |
|---|---|
| **W1** | The `web_chat` config: public key, allowed origins, per-config CORS, sealed Turnstile secret, privacy settings. The mandatory spend limit. The anonymous config read. |
| **W2** | Sessions: Turnstile verification, `web_sessions`, the token, the `SESSION_ID` identity, the 30-day sliding expiry and its purge, consent. |
| **W3** | Messages over `/web/v1`: ingest into the existing pipeline, polling with `awaiting_reply`, Redis rate limits and message bounds. |
| **W4** | The widget (`frontend/web-widget`): one `<script>` with the public key, Shadow DOM, chat, AI notice, privacy notice or acceptance, polling. |
| **W5** | What the first shop needs (amendment "The first shop: CauceWebs"): a case's generic contact, the `demo` category and the plans' `estado` attribute. Stock and orders wait for a shop with stock. |
| **W6** | The first shop's pack and the harness: `examples/caucewebs/`, a fixture-agnostic run, its scenarios, and a first baseline. |
| **W7** | The shop-assistant mode (WC8) on the landing page and on `/pagar`: bubble, signals by route, templates, page context, `show_products` with plan cards (with ADR 0004's capabilities for `web_chat`), persona, variants and their metrics, and its scenarios. |

The order is dependency first: W1–W3 make the channel work over HTTP and W4 puts it in a page.
W5 and W6 make it a shop's agent, and W7 builds on all of them. Each unit lands with its tests,
and the units that touch a pack also land with a full battery.

## Consequences

- A shop embeds one script and gets an agent that never holds tenant authority in the browser.
- The spend limit stops being optional for the tenants most exposed to anonymous traffic.
- Redis gets its first use (rate limits), and the deployment gains two secrets outside the
  database: the IP-hash key and the Turnstile secrets, the latter sealed.
- Opening metrics are partial by default, by design: the visitor's privacy comes before the
  variant comparison, unless the controller decides otherwise.
- The product card is the first part a channel may not carry, so ADR 0004's capabilities stop
  being deferred for `web_chat`.

## Not decided here

- SSE and its fan-out across instances (WC4).
- Wildcard origins.
- Integrating an external e-commerce or order system (WC6 defines the ports only).
- A server-side purchase webhook, as an alternative to the browser's `trackPurchase`.

## Amendment (2026-11-09): the strategic chat's answers to W0, and the key in the path

- **The opening counter (WC8)** is optional and off by default. It keeps **aggregates only, per
  variant and day**: no session id, no IP, no identifier of any kind travels or is stored.
- **The initial values (WC3, WC4, WC6, WC8) are accepted**, and each config can set its own. A
  rate limit can be set lower than the platform's default, never higher.
- **The anonymous configuration read is accepted.** It is the only request before the first
  message.
- **The IP is used only for the request limits and is never recorded.** It is not stored in the
  database, in an audit entry or in a log line, and it is not sent to Turnstile (`remoteip` is
  omitted). The limits key Redis on an HMAC of it, which expires with its bucket. WC6's per-IP
  limit on failed order lookups follows the same rule: the ingest leaves the HMAC in Redis next
  to the session, expiring with the hour it counts, and the asynchronous tool reads it there.
  Nothing is written on the session row.
- **The public key goes in the path, not in a header** (found while building W1). A browser's
  CORS preflight carries the names of the request's headers, never their values, so a key in
  `X-Cauce-Public-Key` could not tell the preflight which config's origins apply. Every
  endpoint is therefore under `/web/v1/{publicKey}/`:

  | Method and path | Auth |
  |---|---|
  | `GET /web/v1/{publicKey}/config` | none |
  | `POST /web/v1/{publicKey}/sessions` | Turnstile token |
  | `POST /web/v1/{publicKey}/messages` | session token, which must belong to that key's config |
  | `GET /web/v1/{publicKey}/messages?cursor=` | session token |
  | `POST /web/v1/{publicKey}/events` | session token |

  A request without an `Origin` header (not a browser) is not a CORS concern. Turnstile, the
  session token and the limits still apply to it.

## Amendment (2026-11-09): the emergency switch, before W3

Any channel config can be switched off and back on (`POST /v1/channel-configs/{id}/disable` and
`/enable`), with the authority of the rest of the binding and audited as `admin.channel.disabled`
and `admin.channel.enabled`. The switch takes effect at once and deletes nothing.

- **A disabled web chat** still resolves by its key. Its configuration read says `active: false`,
  so the widget can hide itself, and new sessions and messages get 409 `channel_disabled`, a clear
  error rather than an unknown key.
- **Its tenant's spend limit may then be removed.** WC3 holds the limit in place only while a web
  chat is active. Enabling a web chat again needs the limit, as creation does.
- The web channel goes on no public page before W3.
- **What was accepted** (WC5) is the widget's notice text, by its version, together with the privacy
  link. With acceptance, the widget sends `notice_version` with the session request, and the
  session records SHA-256 of `notice:{version}` and `privacy:{url}`. A change to either is a
  different notice.

## Amendment (2026-11-10): the first shop is CauceWebs

The first shop is CauceWebs (`caucewebs.es`) itself, not an e-commerce site with stock. That
changes W5–W7. W1–W4 do not change, except that the widget takes the instance's URL as an attribute,
so it can move from a tunnel to Cauce OS's own server without the page changing.

- **The catalog is the plans** (Básico, Básico con dominio, Plus, Tienda), each with its price and
  what it includes. A declared attribute `estado` says whether a feature is `disponible` or
  `previsto`. There is no stock and no order status, so `CatalogSource.stockOf`,
  `OrderStatusSource` and `get_order_status` (WC6) wait, in `docs/deferred.md`, for the first shop
  with stock.
- **The sale is a demo request.** The agent collects the bar's name, its town and a contact, and
  opens a case with `escalate_to_owner` (category `demo`). The case reaches the owner's bot as every
  case does.
- **A case's contact becomes generic:** a `contact` with its type (`phone` or `email`) and a format
  check by type. An incomplete contact is asked for, never completed (the voice-note rule of ADR 0012
  applied to writing).
- **The agent's rules:**
  - it never invents prices, conditions or features;
  - what is planned is said to be planned;
  - conditions are referred to `caucewebs.es/condiciones`.
- **The shop-assistant mode (WC8)** runs on the landing page and on `/pagar`, with signals by route.
  On `/pagar`, the idle cart is a plan chosen and not paid, and there is also exit intent. A card
  is a plan, and its button takes the person to `/pagar?plan=…` through the shop's hook.
  `trackPurchase` is called when the payment is confirmed.
- **Scenarios:**
  - exact prices;
  - the right plan for what the bar asks for;
  - a demo request with complete and with incomplete data;
  - a question about something that does not exist;
  - pressure for discounts;
  - prompt injection;
  - from WC8, a recommendation only from the catalog and correct use of the `/pagar` context. The
    out-of-stock scenario waits with the stock.
- **Tenancy:** a partner "CauceWebs" with a client "CauceWebs · ventas" for this agent, since the
  spend limit is set on a client.
- **Privacy:** an informational notice (`NOTICE`) linking to caucewebs.es's privacy policy, which
  CauceWebs updates before publishing.
- **Deployment:** the first test runs on a hidden page against an instance behind a tunnel, with
  `https://caucewebs.es` as the allowed origin. Production runs on Cauce OS's own server, never on
  CauceWebs's.

## Amendment (2026-11-10): how W3 counts

- **Fixed windows, not token buckets.** Each bucket of WC3 is a Redis counter per channel, subject
  and window, incremented and given its lifetime in one atomic script, so a counter never outlives
  its window. The bounds are the same per window; across a window's edge the rate can reach twice
  the limit, which is accepted for one counter per subject.
- **Order:** a session's open counts its IP before Turnstile is asked, so a flood never reaches
  Cloudflare. A message counts its IP, then the session's minute and day, before the ingest.
- **Redis down refuses the request** (503 `rate_limit_unavailable`): an unlimited anonymous
  surface would be worse than an unavailable one. Past a limit the answer is 429 `rate_limited`
  with `Retry-After`.
- **The IP's HMAC key** is `cauce.web.ip-hash-key` (base64, 32 bytes), outside the database.
  Without it, each process uses a random key of its own and logs a warning, which keeps the per-IP
  limits per instance. `cauce.web.trusted-proxies` lists the exact addresses whose
  `CF-Connecting-IP` is believed.
- A channel's own limits are set at creation (`limits`), each positive and no higher than the
  platform's (`cauce.web.*`).

## References

- `docs/adr/0001-rls-escape-hatches.md`: resolving a tenant without context
- `docs/adr/0002-authority-model.md`: who sets the spend limit
- `docs/adr/0004-identity-message-parts-and-channel-capabilities.md`: `SESSION_ID`, message parts,
  capabilities
- `docs/adr/0006-whatsapp-cloud-api-channel.md`: sealed channel secrets
- `docs/adr/0008-one-time-source-the-database-clock.md`: expiries and purges
- `docs/adr/0010-usage-prices-and-spend-limits.md`: the spend limit
- `docs/adr/0013-new-thread-after-inactivity.md`: R2
