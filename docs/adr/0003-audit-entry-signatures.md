# ADR 0003: Audit entry signatures

Status: Accepted (not yet implemented)

## Context

Every runtime and administration event of a tenant is recorded in an append-only
ledger whose entries are hash-chained: each entry's hash is computed over its own
fields plus the hash of the entry before it. A verification endpoint recomputes
the whole chain on demand and reports whether it is consistent.

This detects any alteration made by someone who cannot recompute the chain. It
does not detect a wholesale rewrite by an actor who can: recompute every hash
from the point of change onward, rewrite the head, and the result is
indistinguishable from an honest chain. The verification response says so, in
those words, in every reply.

Closing that gap requires a signature over each entry, made with a key the
database does not contain. The threat model driving this decision identifies:

- **A0**, the honest operator who makes an operational mistake (restores a
  backup, runs two instances against one database). The most frequent case, and
  not an attack.
- **A1**, an insider with database access but not deployment access. **The
  primary actor this layer defends against.**
- **A2**, an external attacker with transient database access. Equivalent to A1
  for defensive purposes.
- **A3**, an operator with deployment access, and therefore access to the signing
  key. **Explicitly out of scope**, and stated as such in the product.
- **A4**, a party disputing what happened. Needs an exportable artefact, not a
  defence.

The verifier this phase targets is the operator's own client: someone who trusts
the operator's deployment but not necessarily everyone with database access.
Convincing a third party who distrusts the operator is a later problem and needs
external time anchoring.

## Decision

### 1. Sign the chained entry hash, over a versioned preimage

The signature covers the entry's existing chained hash rather than a new
serialisation. That hash already includes the previous entry's hash, so signing
it commits to the entire prefix of the chain.

The preimage moves from an implementation detail to a **published format**. It is
currently canonical JSON with code-point-ordered keys, no whitespace, over eight
explicit fields including `prev_hash`, `payload_hash` and a literal
`scheme` discriminator. That shape is kept. Three changes make it reproducible by
an independent implementation, and they define **preimage v2**:

- **`id` enters the preimage.** It is currently the only column outside the hash
  that an actor could alter without detection.
- **Timestamps get fixed precision.** `Instant.toString()` omits the fractional
  part when it is zero, so the same instant can serialise two different ways. v2
  always emits microsecond precision, including trailing zeros, so an external
  verifier can reproduce it from the stored `timestamptz` without guessing.
- **Unicode is normalised to NFC before hashing.** Otherwise two byte sequences
  with identical meaning produce different hashes.

Entries written under v1 remain verifiable under v1 rules; the existing
`hash_scheme` column already carries the discriminator. **v1 entries are never
signed** — the capability did not exist when they were written — so the signature
layer starts clean on v2 and nothing is ever re-hashed or re-signed
retroactively.

The signature preimage is the entry hash together with the `key_id`, the
signature scheme version, and a domain separation string. Domain separation keeps
a signature made in this context from being valid in another one added later,
such as the export artefact.

The preimage specification moves out of Javadoc and into `docs/`. An independent
verifier needs a document, not a comment in a Java class.

### 2. Ed25519

Signatures use Ed25519, available natively in the JDK since 15. No new
dependency.

Ed25519 is deterministic: it needs no per-signature nonce, which removes an
entire class of implementation failure where a repeated or predictable nonce
leaks the private key. When correctness depends on nobody making a mistake,
choosing the scheme that offers fewer mistakes to make is the right trade.

It is also present in the standard library of every language an independent
verifier is likely to be written in, and produces 64-byte signatures from 32-byte
keys.

The alternative considered was ECDSA with P-256, which interoperates better with
European qualified-certificate tooling. That matters for a third-party verifier,
not for this phase's verifier, and the `signature_scheme` column allows adding a
second scheme later without invalidating anything already signed.

Signatures are stored base64-encoded.

### 3. One key per instance, held outside the database

The requirement is that the database does not contain the private key — that is
what makes an entry unforgeable by A1 and A2. The key lives in process
configuration, the same mechanism already used for the API-key pepper.

No hardware security module. An HSM defends against an actor with deployment
access, which is A3, which is out of scope. Adding it would buy nothing the
threat model asks for.

Scope is **per instance**, not per tenant. Per-tenant keys would not improve
isolation against any actor in the model — A1 and A2 reach no keys either way,
A3 reaches all of them — while multiplying the number of keys to rotate, back up
and distribute. Because `key_id` is recorded on every entry, moving to per-tenant
keys later is additive.

**Rotation.** A new key gets a new `key_id`. New entries are signed with it; old
entries are never re-signed, because re-signing is rewriting history. Verification
selects the public key by the entry's `key_id`.

**Public keys are never retired.** A private key is decommissioned; its public
counterpart must outlive it, or everything it signed becomes unverifiable. The
registry of `key_id` to public key is **published outside the database** — in this
repository — so that an actor with database access cannot substitute a public key
and pass forged signatures as valid. Each self-hosting operator publishes their
own registry; this is a format, not a central authority.

**Compromise.** A compromised `key_id` is marked as such in the registry.
Verification **reports** it rather than failing: the response says the entries
verify and were signed with a key marked compromised on a given date. What to
conclude from that belongs to whoever is auditing, not to us. Note the honest
limit: without reliable timestamps, entries signed *before* the compromise are
not distinguishable from entries signed after it. Only external time anchoring
changes that.

### 4. Four verdict states

The verification result stops being binary. Distinguishing these is what
separates A0 from A1:

| State | Meaning |
|---|---|
| `VALID` | Recomputation and signatures consistent from genesis to head. |
| `BROKEN` | An inconsistency was found. Carries the break point and classification. |
| `TRUNCATED` | The chain ends earlier than expected and what is present is consistent. The signature of a restored backup, not of an alteration. |
| `UNVERIFIABLE` | No verdict can be issued: a public key for a present `key_id` is missing, or entries carry an unknown signature scheme. Neither good nor bad — **no answer**. |

`UNVERIFIABLE` exists to avoid the easiest available lie: calling a chain broken
when it is merely unreadable by this verifier.

### 5. Verification is recorded in the chain

Each verification appends an entry recording who verified, when, and the result.

This makes the ledger contain the history of its own verifications. If it is on
record that the chain verified as valid on a given date, any later manipulation
is bounded in time. It is a weak internal anchor — not binding on a party who
distrusts the operator — but it costs nothing and strengthens the current level
without waiting for external anchoring.

Two constraints follow and are part of this decision:

- The verification entry becomes part of what the next verification checks. This
  recursion is correct and intentional.
- **Verification is on demand only.** It must not be attached to a scheduler or
  to each write, or the ledger grows without adding information.

## Consequences

### What can be claimed after this lands

> Each ledger entry is hash-chained and signed with a key the database does not
> contain. Verification recomputes the chain and checks every signature. This
> detects any alteration made by someone without access to the signing key. It
> does not detect a rewrite by someone who controls the deployment, and with it
> the key.

### What cannot be claimed, or implied

Nothing about regulatory conformity, admissibility, certification, or absolute
resistance to manipulation. Two mechanical defences of this already exist: a
vocabulary guard in the playground build, scoped to the screen that renders the
verdict, and an integration test asserting the same word list over the serialised
chain-verification response. Neither reaches the surfaces this work adds — the two
new verdict states, the key registry and its compromise marking, and whatever the
export artefact eventually says about itself. Extending both to cover them is part
of this unit, not something already in place.

### Costs

- A schema migration adding `key_id` and `signature_scheme` alongside the
  existing `signature` column. Only `signature` exists today.
- Two verdict states to add, in the API response and in the playground screen
  that renders it.
- An operational procedure that did not exist: generating, holding, backing up
  and rotating a signing key. Losing the private key stops new signing; losing
  the public key makes past entries unverifiable. They must not be stored
  together.
- Verification becomes more expensive: a signature check per entry on top of the
  hash recomputation, on an operation that is already O(n) and uncached.

### Deferred, deliberately

- **Export artefact and independent verifier.** A self-contained JSON document
  with entries, signatures, the public keys needed, and the `prev_hash` anchoring
  the start of the range; plus a small program that verifies it without Cauce's
  code. Deferred to its own unit: signatures must be settled first, and an export
  without signatures proves little. When it ships, it must state plainly what it
  demonstrates and what it does not — that entries have not changed since they
  were signed with key X, not when they were signed nor that the holder of X did
  not fabricate them.
- **External time anchoring.** No structural work is needed now: because every
  hash includes the previous one, publishing the head hash is enough to commit to
  the entire chain up to that point. The choice of medium — an open timestamping
  protocol, or a qualified timestamping authority — is made when a client asks,
  and may well be both.
- **Per-tenant keys.** Additive, thanks to `key_id`.

## Notes

The uniqueness constraint on `(tenant_id, sequence_number)` this decision would
otherwise require already exists, along with head-row locking. Two processes
cannot silently interleave entries for one tenant.

## References

- `backend/cauce-memory/src/main/resources/db/migration/V21__create_audit_log_entries_table.sql` — the append-only ledger and the reserved `signature` column
- `backend/cauce-memory/src/main/resources/db/migration/V22__add_audit_chain_columns.sql` — `payload_hash` / `hash_scheme`, and the nullable `payload` that makes redaction survivable
- `backend/cauce-memory/src/main/resources/db/migration/V23__create_audit_chain_heads_table.sql` — the per-tenant head row and its locking
- `backend/cauce-governance/src/main/java/dev/cauce/governance/audit/AuditChainHasher.java` — the v1 preimage, today specified in Javadoc
- `backend/cauce-governance/src/main/java/dev/cauce/governance/audit/CanonicalJson.java` — the canonical JSON form the preimage is hashed over
- `backend/cauce-governance/src/main/java/dev/cauce/governance/audit/AuditChainVerifier.java` — the recomputation this decision extends
- `docs/adr/0001-rls-escape-hatches.md` — the role separation that keeps the ledger append-only at the database
