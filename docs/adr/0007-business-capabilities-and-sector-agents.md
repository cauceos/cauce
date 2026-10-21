# ADR 0007: Business capabilities and sector agents

Status: Accepted and implemented (B1–B12).

## Context

The first vertical agent is a customer-facing agent for a bar with no software, on Telegram:
it answers from the bar's sheet, answers about dishes and allergens from the loaded menu
only, takes and changes table bookings, hands what it cannot handle to the owner, and the
owner gets a nightly summary. An owner-facing agent (suppliers, accounts, staff) is phase 2.

What the platform had when this was decided:

- A hardened agentic loop (ADR 0005): per-agent tool grants (C4), every call and result in
  the audit chain by hash (C5), sanitized tool errors, a fallback reply.
- A tool SPI whose `execute(ToolCall)` receives the call and nothing else. The tenant is
  implicit (the worker's `TenantContext`); the agent, the conversation, the interlocutor and
  the trigger message are not available to a tool.
- No business data: no sheet, no menu, no agenda, no bookings, no record of a case handed
  to a human, no way to send a message to anyone but the interlocutor of the current thread.
- A conversation status `ESCALATED` with no caller. The live thread of an interlocutor is
  resolved among OPEN conversations only, so an escalated conversation would make the next
  message of the same person open a new thread.
- No way to edit an agent's prompt: an agent is created, its tools replaced, nothing else.
- ADR 0006's design note: anything proactive is designed against WhatsApp's 24-hour window
  even while it only runs on Telegram.

The decisions below were set in the plan of this unit and validated before implementation.

## Decision

### B1 — Generic capabilities; the sector is data

The code knows seven sector-neutral notions: the business **profile** (name, time zone,
facts), the **catalog** (items with declared attributes), the **agenda** (shifts with a
capacity, and bookings against them), **handoffs** (a case handed to the owner, with a
configurable category), **unanswered questions**, **owner notifications**, and the
**daily digest**. A bar and a hairdresser use the same tools.

What is hospitality — the prompt, the tool grant, the escalation categories, the declared
attribute `allergens` with the fourteen EU allergens, the shifts and the sample data — is a
**sector pack** of plain files under `examples/hosteleria/`, loaded through the API. No
hospitality word appears in the Java code.

### B2 — Each capability behind a core port, with a native adapter

The tools (`cauce-tools`) depend on ports in `cauce-core` only: `BusinessProfileSource`,
`CatalogSource`, `BookingProvider`, `HandoffSink` and `KnowledgeGapSink`. The native
adapters live in a new module, **`cauce-business`** (depends on core, memory and tenancy),
which also owns its domain, persistence, services, the notification outbox and the digest.
An external system (Google Calendar, Odoo) is another adapter of the same port: the agent,
its tools and its scenarios do not change.

There is one adapter per port. Choosing an adapter per tenant (a String identifier validated
by the service, like `model_provider`) arrives with the second adapter of a port, where the
choice has somewhere to be stored; building the selection with one candidate would be a
registry of one.

### B3 — The model never chooses whom an action affects

`Tool.execute` receives a `ToolExecutionContext` built by the orchestrator: the tenant, the
agent, the conversation, the interlocutor's identity, the trigger message and the invocation.
A tool takes the subject of its action from the context, never from the model's arguments.
An interlocutor can see, change or cancel only their own bookings; a reference to someone
else's booking is, to the model, a booking that does not exist.

### B4 — The sheet and the menu are read through tools

The agent reads the profile and the catalog through tools, not through text injected into
the prompt: every lookup is then a C5 audit record, and a scenario can assert that the
answer was grounded in a lookup made in the same turn. The required scenario: **no reply
about dishes or allergens without a catalog call in the same turn.**

The catalog declares which attributes are mandatory to state (`declared_attributes`). For
those, an item without the attribute is returned as `NOT_DECLARED` — explicitly, never as
an omission the model could read as "none". An empty list is a declaration ("contains
none"); an absent attribute is not. A question the sheet does not answer is said to be
unknown and recorded as an unanswered question by a tool.

### B5 — One profile, one catalog and one agenda per business tenant

Each is owned by a CLIENT tenant (the business; agents are CLIENT-only already) and is
unique per tenant. Two venues are two client tenants. **Conscious limitation:** several
venues of one business with a unified view (one agent booking across venues, one digest for
all) is a future case and needs an owner of the venues above the per-venue data.

### B6 — Bookings: shifts, capacity, holds, and confirmation in two turns

An agenda declares shifts (days of the week, start, end, capacity in units such as covers),
closed dates, the largest party it books without a human, and how far ahead it books. A
booking consumes units of one shift on one date.

- **A proposal holds capacity.** `propose_booking` (and a change, and a cancellation) only
  proposes: it creates a HELD booking, or marks a pending change, that expires after the
  agenda's hold time. Availability counts CONFIRMED bookings and unexpired holds.
- **Confirmation needs a later turn of the interlocutor.** A proposal records the trigger
  message it was made in; confirming it is valid only from a different, later trigger
  message. "Confirm the details before closing" is then enforced by the code: the model
  cannot propose and confirm in the same turn, whatever its prompt says.
- **No overbooking under concurrency.** Every booking write locks the tenant's agenda row,
  then rechecks capacity.
- **A changed booking** is a new proposal that replaces the old one: on confirmation the new
  one is CONFIRMED and the old one CANCELLED in the same transaction, and the capacity check
  excludes the booking being replaced.
- A party above the self-service limit is refused by the tool with an instruction to hand it
  to the owner (B7).

### B7 — A handoff is a record; the conversation stays OPEN

Escalating to the owner creates a **handoff** (category, summary, the contact data the agent
collected), queues an owner notification (B9) and emits `conduct.human.escalation` — the hook
reserved since the audit unit, now with a caller — with ids and the category only. The
conversation is not moved to `ESCALATED`: that would split the thread, and nobody takes over
the conversation in this phase. The agent keeps talking to the customer; it tells them the
owner will contact them and never promises prices, availability or exceptions. Handoffs are
listed and resolved over the API.

### B8 — The owner has their own bot from phase 1, bound by a link code

The owner talks to a **second Telegram bot**, a channel config bound to an owner agent (phase
1: no tools, it says what it cannot do yet). The owner never talks to the customer agent.

An owner is bound to their business by a **single-use link code** issued over the API for
that tenant and that bot: random, returned once, stored hashed (only ever compared), expiring.
The owner sends the code to the owner bot (Telegram's deep link `t.me/<bot>?start=<code>`
fills it in). The channel layer offers each inbound text to the inbound interceptors (a core
port) after the adapter verified and parsed it and **before ingest**; the owner-link
interceptor consumes a valid code, binds the sender's identity as the tenant's owner
recipient, replies with a confirmation and ingests nothing. A redelivery of the same code by
the same identity is answered again and binds nothing new. The binding is audited
(`admin.owner.linked`).

### B9 — Proactive messages go through an outbox and respect the reply window

Every message to the owner (handoff notice, digest) is first persisted as a **notification**
in the same transaction as the fact it reports, then sent by a scheduled dispatcher through
the `OwnerNotifier` port, implemented by the channel layer. The dispatcher finds due
notifications across tenants through a `SECURITY DEFINER` function and sends each under its
tenant's context (ADR 0001).

- **Outside the window, it is not sent.** The window is measured from the owner's last
  message on the owner bot (or the moment they linked). On WhatsApp, outside 24 hours, the
  notification ends `NOT_SENT` with reason `outside_reply_window`: no template, no retry.
  Templates are deferred work. Telegram has no window; a bot that the owner never started
  fails the send, which is recorded the same way.
- **Transient failures are retried** with a backoff up to a bounded number of attempts, then
  `FAILED`.
- **The record is the source of truth.** A notice that never arrives loses nothing: handoffs
  and notifications are listed over the API with their status and reason.
- A tenant with no owner bound gets its notification recorded `NOT_SENT` with reason
  `no_owner_recipient`.

### B10 — The nightly digest is deterministic, with no LLM

At the local time set in the profile, the business gets one digest: tomorrow's bookings, open
handoffs, and the unanswered questions since the previous digest. It is composed by code from
the records, not by a model: no cost and nothing invented. It is unique per (tenant, local
date), so a re-run never sends a second one, and it goes through the outbox of B9 with the
same window rule.

### B11 — Where an MCP client fits (not built)

- **C4.** MCP becomes a second source of tools beside the registered beans, scoped per tenant,
  with namespaced names (`<server>__<tool>`, within the providers' `^[a-zA-Z0-9_-]{1,64}$`).
  Nothing here assumes the catalog is static beyond `ToolCatalog` being a port; tenant-aware
  validation comes with it.
- **C5.** Every tool, native or MCP, goes through the same dispatch, so the audit by hash
  covers MCP calls unchanged; an MCP tool with side effects or external reads emits the
  reserved `conduct.external.action` / `conduct.data.access`.
- **W3.** MCP server credentials are sealed with `SecretCipher`, per tenant — the path
  per-tenant LLM credentials will take.
- **Two ways in.** An MCP-backed adapter **behind a port** (a calendar server behind
  `BookingProvider`): the customer agent keeps the stable Cauce tools and its scenarios. Or raw
  MCP tools granted to an agent: fitting for the owner agent of phase 2. The customer agent
  takes the first.

### B12 — Scenarios as data, before the harness

Each task has acceptance scenarios written as data under `examples/hosteleria/evals/`: the
fixture, the interlocutor's turns, and what is checked — tools called (the C5 trace), records
created, and constraints on the reply text. The guarantees enforced by code (two-turn
confirmation, ownership, capacity, `NOT_DECLARED`, the window, digest uniqueness) are also
integration tests. The harness that runs the scenarios against a model is `cauce-evals`, not
built here.

## Consequences

- `Tool.execute` changes signature: a breaking change of the tool SPI, made while the only
  tool is the clock.
- New migrations for profiles, catalogs, unanswered questions, agendas and bookings,
  handoffs, owner recipients and link codes, notifications and digests, each with
  hierarchical RLS; two `SECURITY DEFINER` functions for the cross-tenant scans.
- The WhatsApp adapter's window refusals become a distinguishable subclass of
  `ChannelDeliveryException`, so a proactive sender can tell "not allowed now" from "failed".
- `conduct.human.escalation` leaves the reserved list.
- The hold time and the two-turn rule add a round trip to every booking: deliberate.

## References

- `docs/adr/0001-rls-escape-hatches.md` — the cross-tenant scans
- `docs/adr/0005-agentic-loop-hardening.md` — C4 and C5
- `docs/adr/0006-whatsapp-cloud-api-channel.md` — W3 and the design note on the window
- `docs/adr/0008-one-time-source-the-database-clock.md` — the clock holds, link codes, notices and
  digests are measured on
- `examples/hosteleria/` — the sector pack, the reference bar and the scenarios
