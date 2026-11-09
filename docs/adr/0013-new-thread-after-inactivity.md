# ADR 0013: A new thread after inactivity, and the person's open cases by tool

Status: Accepted, not implemented. Records R2 as decided in the strategic chat on 2026-11-09, in
two units: R2a (`list_my_cases`) and R2b (closing on inactivity). R2a lands first, because R2b
only works if nothing depends on the history. Confirmed the same day: a FALLBACK counts as
activity, and an invocation in flight keeps its thread because the reaper bounds that case (T2).

## Context

An OPEN conversation never closes on its own (`docs/deferred.md`, R2). A person who comes back
days later is put back into the same thread, and the agent reads the whole history again:

- **Cost grows with the history.** Every model turn carries the thread. G3, ten messages, already
  spends 18,666 input tokens per message (`linea-base.md`, `1138Z`), and that is one afternoon,
  not a regular customer over weeks.
- **Old context leads the agent astray.** H3.19 is the real failure of 2026-10-24: a thread
  opened two days earlier said "Hoy es jueves 22", and the agent worked "mañana" out from that
  instead of from the new message. ADR 0008's amendment (each message carries when it was sent)
  fixed that case. The cause, a thread that outlives its day, is still there.

The history is not memory, though. Two things a person may ask about after a day exist only
as rows the agent reaches through the history or through a tool:

- **Bookings** have a tool: `list_my_bookings` lists the person's upcoming bookings, both
  confirmed ones and ones still waiting for confirmation, from the identity rather than the thread.
- **Cases handed to the owner** (`HandoffSink`, ADR 0007 B7) have no read tool. After a new
  thread, the agent could not say "your group request is with the owner" unless the old history
  were still there.

Two existing facts constrain the design:

- `conversations.last_message_at` is advanced on **every** append (`touchLastMessageAt`), tool
  calls, tool results and SYSTEM records included. It is not the clock of a person's activity.
- `Conversation.close()` stamps `closed_at` with `Instant.now()`, the JVM's clock. ADR 0008
  forbids that for a date compared later.

## Decision

### T1 — R2a: `list_my_cases`, the person's open cases by tool

A lookup tool (`Tool.lookup()` is true), `list_my_cases`, with no arguments, lists the **OPEN**
handoffs of the identity the agent is talking to, in this business: code, category, the
agent's summary, and the local date it was opened. Resolved cases are not listed. Unanswered
questions (`KnowledgeGapSink`) are not cases the person opened, so they are not listed either.

The read goes on the same port the cases are written through: `HandoffSink` gains
`openForIdentity(tenantId, identityId)`, the way `BookingProvider` both writes and lists
(`upcomingFor`). The port keeps its name. Renaming it is not worth the churn. The native adapter
reads `handoffs` under RLS. A person's cases follow their identity, not their thread, so they
survive the new thread exactly as bookings do. Another channel is another identity (ADR 0004).

The pack's prompt tells the agent to use it whenever the person asks about something they
asked for earlier, the same way it already uses `list_my_bookings`.

### T2 — R2b: the thread closes when the next message arrives, never on a timer

Closing happens **inside the ingest transaction of the next inbound message**
(`InboundMessageService`, ADR 0004), right after the OPEN conversation is resolved and the
idempotency key, if any, is claimed: a duplicate that lost the claim never touches the thread. There is no
scheduled task. A thread nobody writes to stays OPEN until someone writes, and closing it then
costs nothing extra.

1. Resolve the OPEN conversation for `(agent, identity, channel config)`, as today.
2. If the agent's window is not 0 and the conversation's last **conversational** message is older
   than the window, close it and start a new OPEN conversation in the same transaction. The new
   USER message goes into the new conversation.
3. Otherwise, append to it as today.

**A conversational message is a USER message or a reply the person received: AGENT or FALLBACK.**
TOOL_CALL, TOOL_RESULT and SYSTEM records do not count, and neither does
`conversations.last_message_at`, which every append advances. The age is computed in SQL against
`now()` (ADR 0008): `now() - max(created_at)` over those roles of the conversation (its
`started_at` when it has none), compared with the window. `closed_at` is stamped with the
database's `now()`. As part of R2b, `close()` takes `now` as an argument, as the domain does
elsewhere, instead of reading `Instant.now()`.

**Outbound notices do not count.** Owner notices, nightly digests and, when they exist, reminders
are not messages of any conversation today (`OwnerNotificationSender` delivers them without an
append). If one ever becomes a message, it enters with a role outside the count. A business
that writes to a person must not keep that person's thread alive.

**Race safety follows the existing pattern.** The close is a conditional
`UPDATE ... SET status = 'CLOSED' WHERE id = ? AND status = 'OPEN'`. The new thread is created by
the insert-first `ON CONFLICT DO NOTHING` + re-`SELECT` on the partial unique index over OPEN
conversations. If two messages race, one closes and both land in the same new thread: the
other finds the thread no longer OPEN and resolves the OPEN one again.

**An invocation in flight keeps its thread.** If the conversation has a PENDING or PROCESSING
invocation, it is not closed, whatever its age. The reply that is coming belongs to that thread.
The reaper bounds the case. A claim older than `cauce.orchestration.worker.reaper.timeout-ms`
(12 minutes by default) is treated as orphaned and goes back to PENDING, or to ABANDONED once its
attempt budget is gone, and retries are bounded by the backoff and the attempt limit. So an invocation stays "in
flight" for minutes, never anywhere near the 24-hour window. The next message after it settles
closes the thread.

Only OPEN conversations are closed for inactivity. ESCALATED and ARCHIVED ones are left alone.

### T3 — The window: 24 hours, on for every agent, set per agent

A column `agents.new_thread_after_hours`, `INTEGER NOT NULL DEFAULT 24`, `CHECK (>= 0)`; 0 means
never. The migration's default turns it on for every existing agent. It is changed through
`PATCH /v1/agents/{id}`, with the same authority as the rest of the agent (ADR 0002). Hours are
the unit: nothing asks for finer.

**The owner's agent follows the same rule.** It is an agent like any other. The owner's thread
closes after 24 hours without the owner writing or the agent answering, and the notices it
receives do not count (T2).

### T4 — A closed thread is audited and counted

Each close for inactivity emits `conduct.conversation.closed` into the subject tenant's chain
(ADR 0002), with the closed and the new conversation ids, `reason: inactivity`, the window in
hours and the idle time in seconds. These are ids and non-sensitive metadata only, per the audit
sink's rule.

It also publishes an `OrchestrationEvent`, `ConversationClosed`, with the invocation of the message
that opened the new thread, the reason and the idle time. `cauce-observability` turns it into a
counter `cauce.conversation.closed{tenant_id, agent_id, reason}`, next to the other
`cauce.conversation.*` meters. The event is observational and published inside the ingest
transaction. The in-memory metrics listener is
exempt from the `AFTER_COMMIT` question (`docs/deferred.md`).

### T5 — Quotes in a case come only from the current thread

A case's `customer_message` must be copied word for word from one of the person's messages in
**this** conversation (`HandoffService.requireQuoted`, already scoped by conversation id). R2
makes the rule bite: after a new thread, the person's words from the previous thread cannot
be quoted, because the agent no longer sees them. It is kept on purpose. What the owner reads
as the customer's words is something the agent saw in its context.

### T6 — Scenarios

- **H3.19 keeps R2 off.** It measures the real failure of 2026-10-24, a thread opened two days
  earlier and still open, which only exists without R2. The scenario sets the agent's window to 0.
- **A new scenario with R2 on:** a previous thread from two days ago in which the person booked.
  They come back and ask about the booking. The checks are: a new conversation was opened and the
  previous one is CLOSED, `list_my_bookings` was called, and the reply gives the booking's day and
  time.
- **One for the cases:** a previous thread in which a case was handed to the owner. The person
  comes back and asks how it is going. The checks are: a new thread, `list_my_cases`, and the case's
  code or category in the reply, with no promise of an answer on the owner's behalf.

The harness gains a scenario-level setting for the agent's window. `hilo_anterior` gains the
ability to leave a booking or a case behind it. It already backdates the messages, which is what
T2 reads.

## Consequences

- A returning person costs what a new person costs, plus the lookups the tools need.
- Nothing the agent must know may live only in the history. Bookings and cases are read through
  tools, and anything a future tool writes must be readable the same way, or R2 drops it.
- A booking proposed and left unconfirmed for more than a day is confirmed in a new thread from
  `list_my_bookings` (it lists those waiting for confirmation), not from the old proposal's text.
- R2b removes the R2 entry from `docs/deferred.md`.
- The web channel (ADR 0014, after R2) keeps its 30-day session independent of this window. A
  session survives a new thread, and R2 closes the thread, not the identity.

## Not decided here

- Summarising the closed thread into the new one: no memory subject exists (ADR 0004). R2 starts
  clean on purpose and relies on tools.
- Closing on a timer, for a dashboard that wants threads closed without a new message.

## References

- `docs/adr/0004-identity-message-parts-and-channel-capabilities.md`: identity and the OPEN
  thread
- `docs/adr/0007-business-capabilities-and-sector-agents.md`: B7, handoffs
- `docs/adr/0008-one-time-source-the-database-clock.md`: the database clock and its amendment
- `docs/adr/0009-eval-harness-for-sector-agents.md`: the scenarios, H3.19
