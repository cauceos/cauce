# ADR 0008: One time source — the database clock

Status: Accepted and implemented.

## Context

Cauce stamps dates in two places. The database stamps some: the claim functions set
`claimed_at = now()` and lease `next_attempt_at = now() + interval`, and columns default to
`now()`. The JVM stamps others: `Instant.now()` in the domain, `Clock.systemUTC()` in a few
services. As long as a date stamped by one clock is only ever compared with the same clock, this is
harmless. It stopped being harmless once a date stamped by the JVM was compared, later, with the
database's `now()`.

`OwnerApiIT` failed intermittently: an owner notice was queued with
`next_attempt_at = Instant.now()` and the dispatcher, right after, claimed nothing, because the claim
asks `next_attempt_at <= now()` and the database's clock was a few hundred milliseconds behind the
JVM's. The Docker VM's clock drifts against the Windows host (measured: +1.1 s, then −0.4 s a few
minutes later), so the test failed or passed depending on the moment. With the database clock forced
30 s behind (libfaketime), the same four owner tests failed every time.

The flake is the visible case of a general defect. In production the application and the database
are different hosts, and with more than one application instance there are several JVM clocks. A
retry scheduled by one JVM and claimed by the database, a hold stamped by one instance and judged by
another, a reply window opened by one clock and measured by another: each is a comparison across
clocks, and each is wrong by the skew, in either direction.

## Decision

**Every date that is written to be compared later, and every comparison against "now", uses the
database clock.** There is one source of time: the PostgreSQL server's.

How, depending on where the computation runs:

1. **In SQL, with `now()`.** A query that compares with the present uses the database's own
   `now()` (or `clock_timestamp()`), never a Java `Instant` passed in as "now". The claim of pending
   invocations and of owner notices, and their leases, already work this way.
2. **In Java, with the application's `Clock`.** The application has exactly one `java.time.Clock`
   bean, `DatabaseClock` (`cauce-memory`). Each reading is `SELECT clock_timestamp()` on the current
   transaction's connection (a short-lived one outside a transaction). Services inject `Clock` and
   pass its reading into the domain, which takes `now` as an argument rather than reading a clock.
   - `clock_timestamp()` and not `now()`: `now()` is frozen at the start of the transaction, so two
     messages appended in one transaction would tie; `clock_timestamp()` keeps moving. A later
     transaction's `now()` is never earlier than a reading taken in an earlier, committed one, so a
     date stamped from this clock is due when a later query says it is.
   - There is no fallback. A Spring context without `cauce-memory` has no `Clock` bean and fails to
     start rather than silently using the JVM's.
3. **Durations may be measured locally, on a monotonic ticker.** What the rule forbids is mixing
   wall clocks. A remaining lifetime computed against the database clock and then counted down by
   `System.nanoTime()` (the API-key cache's per-entry expiry) never consults the host's wall clock.
   HTTP timeouts, retry sleeps and scheduler delays are durations too, and stay local.

### What falls under the rule

The sites this decision converted, all of which compare a stored date with "now" or with each other:

| Date | Compared by |
|---|---|
| `pending_invocations.next_attempt_at` (retry backoff) | the claim function's `now()` |
| the reaper's orphan threshold | `claimed_at`, stamped by the claim's `now()` |
| `owner_notifications.next_attempt_at` (queued notice) | the dispatcher's claim |
| `bookings.hold_expires_at`, `cancellation_expires_at`; a shift's start; "today" | `BookingService`, the booking tools' `waiting_for` |
| `owner_link_codes.expires_at`; `owner_recipients.linked_at` | the link-code check; the reply window of a proactive notice |
| `knowledge_gaps.created_at`; a digest's `covered_to` | the digest's `[from, to)` window |
| `messages.created_at` | the WhatsApp 24-hour window; context assembly reads the thread in this order |
| `api_keys.expires_at` | authentication, the API-key cache, the listing's `EXPIRED` status |
| the clock tool's "now" | the agent's "today" must be the one bookings are validated against |

### What does not

- **Record timestamps that nothing compares**: `created_at` and `updated_at` of aggregates, shown to
  callers or used only to order rows written by one process. They stay domain-stamped. The moment
  one of them is compared — a retention purge, a usage-by-period query — it falls under the rule and
  its stamp moves to the database clock in the same change.
- **Event timestamps** of the observational `OrchestrationEvent` stream, never stored or compared.
- **The audit chain.** Its timestamps are part of the specified, hashed format
  (`docs/spec/audit-chain-format.md`) and are not compared with "now"; changing their source is a
  format question, not this one.

## Consequences

- Correctness no longer depends on the host clocks agreeing. The full backend suite passes with the
  database clock 30 s behind and 30 s ahead of the JVM (libfaketime on the test Postgres image), the
  configuration in which it failed before.
- **One database round trip per reading, measured as negligible.** A reading costs what a
  `SELECT 1` costs: 0.48 ms median, 0.71 ms p99, against Postgres in Docker on the development
  machine — the same as a bare JDBC round trip, so Hibernate adds nothing measurable. Services read
  the clock once per operation and pass the instant down. An inbound message without tools reads it
  about three times (the USER message, the AGENT reply, the WhatsApp window check when the channel
  has one), around 1.5 ms against a model call of seconds; each tool round adds two (its call and
  result messages) and a booking tool one more. A cached API key reads it never; the cache miss,
  which already queries the database, reads it once.
- **Outside a transaction a reading borrows a pooled connection for one statement.** With
  open-in-view disabled, nothing outside a transaction holds a connection while it waits for
  another, so a reading cannot deadlock the pool; under exhaustion it waits for Hikari's connection
  timeout and throws, at points where the same operation already needs the database (delivery
  reads the channel config and the last message first). Inside a transaction it uses the
  transaction's connection and never touches the pool.
- **A date that only SQL compares could be computed in the statement instead** (`now() + interval`),
  and a new query that computes and compares in one statement should do so (point 1). The three
  existing such dates — a retry's and a queued notice's `next_attempt_at`, the reaper's threshold —
  are set by domain transitions saved through JPA, on cold paths (a transient model error, a
  handoff, a five-minute tick). Moving them into SQL would buy no correctness, since both are the
  server's clock, and would take the backoff out of the domain and the threshold into a migration;
  they keep the clock reading.
- Domain methods that need the present take it as an argument (`releaseForRetry(…, now)`,
  `OwnerNotification.queue(…, now)`, `KnowledgeGap.record(…, recordedAt)`,
  `ApiKey.isActiveAt(now)`, `Message.stampedAt(createdAt)`). Unit tests pass a fixed instant and
  assert exact values instead of time windows.
- **Integration tests must not compare a stored date with the JVM's `Instant.now()`.** They read
  the database's time, or compare stored dates with each other.
- A future purge computes its cutoff in SQL (`created_at < now() - interval '…'`) over columns
  stamped by the database clock.
- Verifying the rule: build a Postgres test image with libfaketime (`FAKETIME=-30s`, running as the
  `postgres` user so its shared-memory semaphore is usable), tag it locally as the image the
  Testcontainers setups use, run the full build, and restore the original tag.

## Amendment (2026-10-23): a clock tells time, never order

One source of time is not enough to order by time: the database's wall clock can step back too
(an NTP correction, a VM resuming). On 2026-10-23 it did, on the development machine after it
resumed from sleep: a conversation read `ORDER BY created_at` put the person's "Sí" before the
agent's previous reply, the request ended on the agent's turn, and the provider refused it.

- **The order of a conversation is its `seq`** (V44): a position assigned on insert from a
  per-conversation counter, under the conversation's row lock, so it follows the order of commits.
  Every read of a conversation orders by it, and the public listing keys its pages on it.
- **The audit outbox drains in `seq` order** (an identity), so the chain follows the order the
  facts were written.
- `created_at` stays a time, for what compares times (windows, cutoffs, expiries). Claims that
  pick due rows by `created_at` only decide who goes first, and their processing order was never
  guaranteed; they are left as they are.

## Amendment (2026-10-24): the model reads the date from each message

The database clock was the system's single source of time, but not the model's. A model has no
"now" of its own: it learnt "today" from a clock tool, and the result of that call stays in the
conversation. In the real test of 2026-10-24, a Saturday, a thread open since Thursday held the
clock reading of that Thursday; asked for "tomorrow", the agent worked from it instead of calling
the tool again, said "tomorrow, Friday the 23rd" and looked up availability on a past date.

- **Each customer message reads with when it was sent.** Context assembly writes in front of every
  USER message `[Sent Saturday 2026-10-24 18:40 Europe/Madrid]`: the message's `created_at`,
  stamped by the database clock, in the time zone of the agent's business (its profile) or, for an
  agent whose business has none, in UTC, said so (`[Sent Saturday 2026-10-24 16:40 UTC]`). The newest one is "now". The prompt of a sector pack says to work
  relative dates out from it, never from memory or an earlier result. The person and the API read
  the message unstamped.
- **A stamp per message, not "now" on the last one.** A message's stamp never changes, so the
  conversation the provider caches reads the same on every later turn; a single "now" moved from
  message to message would change the cached prefix each time. It also tells the model how much
  time passed between two messages.
- **A past date is refused.** Availability of a date before the business's today is a `PAST`
  refusal whose message says what today is, as a booking of one already was: the tool corrects the
  model rather than answering a past day as one with nothing left.

`get_current_time` stays, for the exact time and for a business's zone when the model needs it.
The real failure is a blocking eval scenario of the hospitality pack (H3.19): a thread seeded two
days back, with that day's clock lookup, and "tomorrow" in the new message.

## References

- `backend/cauce-memory/src/main/java/dev/cauce/memory/time/DatabaseClock.java`
- `backend/cauce-tenancy/src/test/java/dev/cauce/tenancy/DatabaseClockIT.java`
- `backend/cauce-memory/src/main/resources/db/migration/V12__pending_invocation_claim_reap_functions.sql`
- `backend/cauce-memory/src/main/resources/db/migration/V37__create_handoffs_and_owner_notifications.sql`
- `docs/adr/0001-rls-escape-hatches.md` — the claim functions
- `docs/adr/0006-whatsapp-cloud-api-channel.md` — the 24-hour window
- `docs/adr/0007-business-capabilities-and-sector-agents.md` — B6, B8, B9, B10
