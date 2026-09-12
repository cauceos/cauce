# Audit chain verifier

A reference verifier for a Cauce audit chain, written against
[`docs/spec/audit-chain-format.md`](../../docs/spec/audit-chain-format.md) and nothing else.

It exists so that "the screen says the chain is fine" can be replaced by "run this yourself".
It imports no Cauce code and no third-party package: the whole program is one file you can
read end to end, and everything it needs — SHA-256, Ed25519, Unicode NFC, string ordering —
is in Node's standard library. If it shared code with Cauce it would prove nothing.

Requires Node 18 or later. There is no `package.json`, on purpose: the absence is the visible
proof that there is nothing to install.

```
node verify.mjs <dump.json> <registry.json> [--json]
node verify.mjs --self-test
```

Exit codes: `0` VALID · `1` BROKEN · `2` UNVERIFIABLE · `3` could not run.

## What it verifies

For one tenant's chain, in sequence order, the checks of the specification's §9 in the order
it makes normative: contiguous sequence numbers, pre-chain rows only as a prefix, a hash
scheme it implements, the chain columns present, the payload hashing to its recorded
`payload_hash`, the link to the previous entry (starting from the tenant's genesis hash),
the entry hash recomputed under the entry's own scheme — v1 or v2 — and, where present, the
Ed25519 signature against the public key the entry's `key_id` names in the registry.

It reports the same three outcomes as the Cauce API, with the same precedence:

- **VALID** — every chained entry recomputed and linked; every signature that could be
  checked verified.
- **BROKEN** — a check failed. The output names the first sequence number and the kind
  (`ENTRY_ALTERED`, `SIGNATURE_INVALID`, `LINK_BROKEN`, `ENTRY_MISSING`,
  `UNCHAINED_ENTRY_OUT_OF_ORDER`, `ENTRY_MALFORMED`), and says what did not match.
- **UNVERIFIABLE** — no check failed, but part of the chain could not be looked at: an entry
  uses a hash scheme this program does not implement (the walk stops there and says so), or
  a signed entry names a `key_id` the registry does not contain. Neither good nor bad: no
  answer for that part.

Unsigned entries are counted, never failed. Every entry written under scheme v1 is unsigned,
because signing did not exist when it was written; so is anything written by an instance
with no signing key configured.

## What it does not establish

Read this before relying on a VALID.

- It establishes that the entries in the dump have not changed since they were hashed and,
  where signed, since they were signed with the named key. **It does not establish when
  they were signed, nor that the holder of that key did not fabricate them.** An actor who
  holds the signing key can rewrite an entry, recompute every hash from there onward,
  re-sign, and the result is indistinguishable from an honest chain. That actor has access to
  the deployment's configuration, not merely to the database.
- Entries without a signature are covered by recomputation only. Recomputation cannot
  distinguish a rewrite by someone with database access from an honest chain.
- A chain shortened to a consistent earlier state — a restored backup — cannot be told apart
  from one that was never longer, unless you hold a head recorded earlier from outside the
  database. The output prints the current head for that purpose; this program does not take
  one back.
- **It does not establish that the dump is the chain the operator holds.** Today the only way
  to produce a dump is direct database access (see below), so the dump comes from the
  operator. What this program checks is the dump you were given.

## Producing a dump

There is no export endpoint yet; the API returns a verdict, not entries. The input is this
program's own dump format — not the export artefact that ADR 0003 defers, which will be its
own unit and may take a different shape. A dump is produced with database access, by the
operator:

```sql
SELECT json_build_object(
  'format', 'cauce-audit-chain-dump/1',
  'tenant_id', :'tenant',
  'entries', COALESCE(json_agg(json_build_object(
      'id', id,
      'sequence_number', sequence_number,
      'outbox_id', outbox_id,
      'event_type', event_type,
      'payload', payload,
      'drained_at', to_char(drained_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
      'payload_hash', payload_hash,
      'prev_hash', prev_hash,
      'entry_hash', entry_hash,
      'hash_scheme', hash_scheme,
      'signature', signature,
      'key_id', key_id,
      'signature_scheme', signature_scheme
    ) ORDER BY sequence_number), '[]'::json)
)
FROM audit_log_entries
WHERE tenant_id = :'tenant';
```

With `psql`: `psql "$DATABASE_URL" -v tenant=<tenant-uuid> -At -f dump.sql > dump.json`.
`drained_at` must be written with six fractional digits in UTC, as above; the program derives
the variable-precision form scheme v1 requires from it (specification §3.1).

The registry is the operator's published key registry, in the format of the specification's
§8.4 — the same file the Cauce instance reads. Its public keys are what the signatures are
checked against, which is why it must come from outside the database.

Run the two together:

```
node verify.mjs dump.json audit-keys.json
```

## Self-test

```
node verify.mjs --self-test
```

Two layers. First, every test vector published in the specification's §10 — the genesis
hash, both entry preimages character by character, the payload hashes where the schemes
agree and where they diverge, the encoding corner cases, and the signature vector — is
recomputed by this program and compared. Second, `fixtures/sample-chain.json` and
`fixtures/sample-registry.json`, a three-entry chain (two v1, one v2 signed) produced once by
the shipped Cauce implementation, are verified untouched and then under sixteen mutations,
each with the outcome the specification dictates: a payload altered, a hash altered, a link
cut, an entry removed, a signature flipped, a partial signature triple, a missing chain
column, two defects on one entry (precedence), an empty registry, an unknown hash scheme, a
redacted payload, a payload re-encoded from NFD to NFC, and pre-chain rows as a prefix.

The fixtures are data. The sample chain's signing key was generated for the fixture and
discarded; only its public half is in the registry file.

## Reading the code

`verify.mjs` is organised in the order the specification is: a strict JSON reader (kept
instead of `JSON.parse` because §4.5 defines canonical numbers on their decimal text, which
`JSON.parse` discards), canonical JSON (§4), timestamps (§3.1), the hash and signature
functions (§5–§8), the registry (§8.4), the dump, the walk (§9), reporting, and the
self-test. Comments cite the section each function implements. Where the program and the
specification disagree, the specification wins.
