# ADR 0004: Interlocutor identity, message parts, and channel capabilities

Status: Accepted (not yet implemented)

## Context

Three parts of the domain model were shaped by the first channel, `api`, and the
first adapter, Telegram, both of which deliver plain text from a single
identifier. Each has since shown the same symptom: the model is narrower than
what the next channel delivers.

**Identity.** A conversation stores the external user as one opaque string,
`external_identity_ref`, stamped by whoever ingests: the REST caller for `api`,
`chat.id` for Telegram. The core requires it to be non-blank and strips it;
nothing else. The live thread is resolved by the tuple
`(agent, channel_type, external_identity_ref)`, arbitrated by a partial unique
index over OPEN conversations. This works while every channel identifies a person
by exactly one string. WhatsApp does not: it delivers the same person under a
phone number and under a provider-internal id, and which field carries which
varies by provider. Two adapters writing the same fact in two spellings would go
unnoticed until the day someone needs to join them.

**Message content.** A message is one text `content` plus, for the two tool roles,
a structured `tool_content` jsonb whose shape is enforced by a CHECK constraint.
That second column was the first sign that "one message, one content" was too
small: the tool loop needed structure, and the answer was a sibling column that
says the same thing in a second form. A Telegram photo with a caption is two
things in one message; with a single content there is no honest place for the
second one.

**What a channel can carry.** Nothing declares it. The outbound half of the SPI
takes text and delivers it. When a message stops being text, the engine either
assumes every channel can carry everything and fails on the ones that cannot, or
assumes the least common denominator and never uses what a channel offers.

The private record of the channel SPI as built names these three as open
decisions 1, 2 and 3, each with WhatsApp as the forcing case, and asks that the
decision cite it. This document does.

The audit trail is affected by the second decision, and the shape of that effect
was checked before writing this: the chain commits to `payload_hash`, and a
message's content enters an audit payload only as `content_hash`, computed over
the message text with a domain prefix. That hash is not in the published chain
format, and the independent verifier cannot recompute it, because it never sees
the message. Changing what a message is changes that hash, not the chain
preimage.

## Decision

### 1. The interlocutor's identity is a domain entity

An **identity** is an entity with its own table, owned by the tenant that owns the
agent: `channel_type`, `kind`, `value`, under hierarchical RLS like every other
row. A conversation references an identity instead of storing a string. Resolving
the live thread becomes two steps in one transaction: resolve-or-create the
identity, then resolve-or-create the OPEN conversation for `(agent, identity)`.
Both steps use the insert-first, race-safe pattern the conversation already uses.

`kind` is a **closed value of the domain**, an enum with a CHECK constraint, not
free text. If it were free, one adapter would write `phone` and another
`phone_number`, and the mismatch would surface only when the two had to be
matched. The starting vocabulary:

| `kind` | Meaning | Who produces it |
|---|---|---|
| `PHONE_NUMBER` | A telephone number | WhatsApp, voice |
| `EMAIL_ADDRESS` | An e-mail address | email |
| `PROVIDER_USER_ID` | An identifier minted by the provider, meaningful only within `channel_type` | Telegram (`chat.id`), WhatsApp's internal id |
| `CLIENT_REFERENCE` | Whatever the REST caller supplies for the `api` channel | `api` |
| `SESSION_ID` | A web-chat session | web chat |

Adding a kind is a migration of the CHECK constraint, deliberately: it is a
domain change, and it should look like one.

**The core stores and compares. It does not interpret.** Knowing that a value is a
telephone number, that two spellings of it are the same number, or that a
Telegram `chat.id` in a group is not a person, belongs to the adapter that
produced the value. The core knows the *names* of the kinds, never their formats.
A format check in the core would put channel knowledge on the wrong side of the
hexagonal boundary.

The uniqueness key is `(tenant_id, channel_type, kind, value)`. The same person
talking to two agents of the same tenant is one identity; the same person on two
channels is two, until the memory subject below exists.

**Why an entity and not a value embedded in the conversation.** Two reasons. A
provider can deliver the same person under more than one identifier, and a value
embedded in each conversation has no way to say so. And the memory subject needs
something to point at.

**The memory subject: deferred, with its place made.** A subject groups several
identities as one person. The distinction that governs the design: **threads are
separate per channel; memory is shared.** The subject does not own conversations.
It is the thing memory is attributed to. That is why conversations do not change
when it arrives: they keep referencing identities, and the subject references
identities from the other side. It is not built now because how two identities
are declared to be the same person is unknown until a real case forces it.

### 2. A message is an ordered list of parts

A message carries an **ordered list of parts**, each of a sealed type. A text
message is a list of one part. The starting types:

- **Text** — plain UTF-8, preserved verbatim.
- **Tool call** and **tool result** — what `tool_content` carries today, absorbed
  into the list. The two forms of saying one thing become one form.
- **Binary reference** — media type, size, the content's hash, and an opaque
  reference to where the bytes live. **Bytes never enter the database.**

The tool roles keep their meaning; what changes is that their payload is a part
like any other, and the CHECK that ties a role to a sibling column goes away.

**Why a list.** A photo with a caption is two parts. With a single content there
are two options, both bad: choose one and lose the other, or invent an
"image-with-text" type, then another for the next combination. A list has no
next combination.

**Binaries by reference and hash.** The message stores metadata and the hash of
the content; the binary lives outside. This is the same pattern as
`payload_hash` in the audit ledger, for the same reason: the record commits to
the hash, never to the bytes. An attachment can be removed on the interlocutor's
request without touching the message row or the audit chain, because neither
held the bytes. This is the audit sink's existing rule applied to messages: no
erasable content in a place deletion cannot reach.

**The audit content hash becomes v2.** Today's `content_hash` is computed over the
message text under the prefix `cauce-content:v1:`. With parts there is no single
text, so the hash gains a v2 computed over the canonical JSON of the parts list,
where a binary part contributes its own content hash and reference, never bytes.
Both versions coexist; nothing already recorded is recomputed. The v1 definition
was never published; v2 will be, as an informative addition to the chain format
specification, so that anyone holding the business row can recompute it. **The
chain preimage does not change.** The chain commits to `payload_hash`, which
commits to whatever `content_hash` the emitter wrote. The independent verifier
learns nothing new: it could not recompute `content_hash` before, and cannot
after. This was the point checked before writing; the alternative, a preimage
v3, would be a format change with no cause.

**The `content` column does not survive.** Keeping it as a derived rendering
beside `parts` would recreate exactly the pair this decision removes. The REST
representation may expose a derived `content` for readers that only want text;
the database has one source of truth.

### 3. The adapter declares what it can carry

Each channel adapter declares its **capabilities**: which part types it accepts,
and their limits (size, count, media types). The core consults them **before**
attempting delivery.

The core does not know what a sticker is and does not translate formats. Turning
an image into a link, or splitting a long text into several messages, is
channel knowledge, and putting it in the core would move channel business across
the boundary the hexagonal architecture exists to keep.

**No fit means a loud failure, with the reason recorded.** No automatic
degradation. This is consistent with the rest of the system: a signing key that
fails does not degrade to writing unsigned, and an unverifiable chain is not
dressed up as a verdict. Degradation policies, if any, will be decided with real
cases in front of them, per channel, and will be visible as policies rather than
as silent behaviour. Today delivery is best-effort and the reason lands in the
log; when delivery becomes transactional, the reason persists with it.

## Consequences

### What this changes, including the uncomfortable parts

**Migrations.** Three, one per decision.

- A new `identities` table with RLS; `conversations` gains `identity_id` and loses
  `channel_type` and `external_identity_ref`; the V13 partial unique index and the
  V3 identity index are replaced by a partial unique index on
  `(agent_id, identity_id) WHERE status = 'OPEN'`. Backfill is mechanical: only
  `telegram` and `api` exist, so every row's `kind` follows from its
  `channel_type`.
- `messages` gains `parts` jsonb, backfilled from `content` and `tool_content`,
  then loses both columns and the `messages_tool_content_shape` constraint.
- No migration for capabilities: they are declared in code.

**The hot path of conversation resolution.** Today: one SELECT, and on a miss an
insert-first upsert plus a re-SELECT. After: the same, twice, chained in the
ingest transaction, for identity and then for conversation. One more round trip
per inbound message. The arbiter of the `ON CONFLICT` moves from a three-column
tuple to `(agent_id, identity_id)`; that arbiter is what makes duplicate webhook
deliveries unable to split a thread, and the change must keep that property. Every
file that names the opaque reference changes: the domain aggregate, the
repository's native upsert, the service, the ingest unit, both halves of the SPI,
the Telegram adapter, the REST request, the playground, and the quickstart.

**The public REST contract.** `POST /v1/agents/{agentId}/messages` keeps
`external_identity_ref` in its body; the server stamps `kind = CLIENT_REFERENCE`
the way it already stamps `channel_type = api`. The message representation
exposes `parts`; `tool_content` goes, and `content` becomes derived. This is a
breaking change to the message DTO, made while there are no external consumers.

**Everything that reads message content.** The LLM mappers for both adapter
modules, context assembly and its token accounting, the conduct audit emitter,
the REST representation, and the playground's conversation view all read `content`
or `tool_content` today and will read parts.

**The published format.** The chain format specification gains an informative
section defining `cauce-content` v1 and v2. The reference verifier does not change.
An implementation that does not update the specification in the same unit as the
hash publishes a format that lies.

**Audit payloads.** `conduct.message.received` and `conduct.agent.responded` carry
`content_hash` v2. Non-sensitive part metadata (count, kinds, media types, sizes)
may join the payload; bytes, references to bytes, and text never do.

### What is not decided here

- **The memory subject.** How two identities are declared one person, who may
  declare it, and how it is undone. Needs a real case.
- **Degradation policies.** Whether any channel ever gets one, and what it looks
  like. Needs real cases, per channel.
- **Where binaries live.** Disk, object storage, or left with the provider and
  fetched on demand. That is deployment, not domain; the message commits to a
  hash and an opaque reference precisely so the answer can differ per
  installation.
- **The exact set of part types beyond the four above** (audio, location,
  document, template). Each arrives with the adapter that produces it, as a new
  case of the sealed type.
- **Whether `content_hash` v2 is checked by any tool.** Publishing the definition
  makes it checkable by anyone holding the row; building the check is separate.

### Implementation cut

Three units, in this order. The criterion is dependency first (capabilities need
the part types), then blast radius ascending: the identity unit rehearses
migrating an entity on the hot path before the unit that touches everything that
reads content.

1. **Identity as an entity.** Table, RLS, `kind` vocabulary, resolve-or-create,
   conversation re-keyed to `identity_id`, backfill, V13 replaced, SPI messages
   carry `(kind, value)`, `api` stamps `CLIENT_REFERENCE`, playground and
   quickstart follow. Independent of the other two.
2. **Message parts and content hash v2.** Sealed part types in the core, `parts`
   column with backfill, `content` and `tool_content` dropped, LLM mappers,
   context assembly, REST representation, playground, conduct audit emitter on
   `cauce-content:v2`, and the specification's informative section, all in one
   unit, because the hash and its published definition must not diverge.
3. **Adapter capabilities.** SPI declaration, the pre-delivery check in the
   outbound service, loud failure with the reason logged, Telegram declaring
   text only.

The first producer of a non-text part (Telegram photo with caption) is a channel
unit after these three, not part of this decision.

## Notes

The private architecture record of the channel SPI as built (ADR-006) lists the
three matters above as its open decisions 1, 2 and 3 and asks that whoever
decides them cite it and update its sections 1 and 5. This document is that
citation; the update is a status note in that record pointing here.

The identity table's uniqueness key includes `channel_type`, which is SPI-bound
and therefore has no CHECK, and `kind`, which is a domain enum and does. That is
the existing convention for the two sorts of column, applied to one table.

## References

- `backend/cauce-core/src/main/java/dev/cauce/core/conversation/Conversation.java` — the opaque reference this decision replaces
- `backend/cauce-core/src/main/java/dev/cauce/core/message/Message.java` — the single content plus tool content this decision replaces
- `backend/cauce-memory/src/main/resources/db/migration/V13__unique_open_conversation_per_identity.sql` — the arbiter index that resolution depends on
- `backend/cauce-memory/src/main/resources/db/migration/V14__add_tool_message_content.sql` — the sibling column absorbed into parts
- `backend/cauce-memory/src/main/java/dev/cauce/memory/conversation/ConversationRepository.java` — the race-safe upsert the identity step reuses
- `backend/cauce-core/src/main/java/dev/cauce/core/audit/AuditContentHash.java` — the v1 content hash, gaining a v2
- `backend/cauce-channels/src/main/java/dev/cauce/channels/spi/ChannelInboundMessage.java` and `ChannelOutboundMessage.java` — the SPI halves that gain identity kind and parts
- `docs/spec/audit-chain-format.md` — §6, where the content hash definition will be published
- `docs/adr/0001-rls-escape-hatches.md` — the tenant discovery the identity table inherits
- `docs/adr/0003-audit-entry-signatures.md` — the coexistence rule (versions live side by side, nothing recomputed) this decision reuses for the content hash
- Private architecture record ADR-006, "Channel SPI tal como está construido" — open decisions 1, 2 and 3
