# Cauce audit chain format

Status: Normative. Covers hash schemes `v1` and `v2`, and signature scheme `ed25519-v1`.

This document specifies how a Cauce audit ledger entry is hashed, chained and signed, in
enough detail to write an independent verifier without reading Cauce's source. Where this
document and the implementation disagree, that is a defect in one of them and worth
reporting — but this document is what an external implementation should follow.

It describes a **computational format**, and verification establishes a computational fact:
that stored entries still hash and link as recorded, and that signatures made with a given
key check out. What that does and does not cover is stated in
[§9](#9-verification) and in the `verification_scope` of every verification response.

Related: [ADR 0003](../adr/0003-audit-entry-signatures.md) records why signatures exist and
what they are for.

---

## 1. Scope

In scope: the entry hash preimage for both schemes, the canonical JSON form both use, the
per-tenant chaining rule, the signature preimage and algorithm, and the published key
registry.

Out of scope: the REST verification surface, the outbox that feeds the ledger, and the
database access rules. None of those are needed to verify a chain you have been given.

Two hash schemes coexist. Entries written before the v2 unit carry `hash_scheme = 'v1'` and
are verified under v1 rules **forever**; nothing is ever re-hashed. A single chain may mix
schemes — `v1, v2, v1, v2` is a correct chain — because instances of different versions may
write it. A verifier reads the scheme from each row, never from a constant.

---

## 2. The ledger row

One entry is one row of `audit_log_entries`.

| Column | SQL type | In the entry hash? | Notes |
|---|---|---|---|
| `id` | `uuid` | v2 only | Primary key. Not in the v1 preimage — see §5.3 |
| `tenant_id` | `uuid` | yes | Owning tenant; also binds the genesis hash |
| `sequence_number` | `bigint` | yes | Per tenant, contiguous, starts at 1 |
| `outbox_id` | `uuid` | yes | The outbox row this entry drained from; unique |
| `event_type` | `varchar(100)` | yes | e.g. `conduct.message.received` |
| `payload` | `jsonb` | **no** | Only its hash is committed to. May be `NULL` — see §2.1 |
| `payload_hash` | `varchar(128)` | yes | Hash of the payload document, §6 |
| `drained_at` | `timestamptz` | yes | When the entry was appended |
| `prev_hash` | `varchar(128)` | yes | Previous entry's `entry_hash`, or the genesis hash |
| `entry_hash` | `varchar(128)` | — | The result: 64 lowercase hex characters |
| `hash_scheme` | `varchar(20)` | yes, as `scheme` | `v1` or `v2` |
| `signature` | `text` | no | Base64 Ed25519 signature over `entry_hash`, §7 |
| `key_id` | `varchar(64)` | no | Which key signed, §8 |
| `signature_scheme` | `varchar(20)` | no | `ed25519-v1` |

`(tenant_id, sequence_number)` is unique, and so is `outbox_id`.

### 2.1 Rows that are not fully populated

Three states are permitted and are not defects:

- **Pre-chain rows.** `prev_hash`, `entry_hash`, `payload_hash` and `hash_scheme` are all
  `NULL`. These were written before chaining existed. They are permitted only as an
  unbroken prefix of the chain: once a chained entry appears, every later entry must be
  chained. They are never back-filled, and a verifier reports their count rather than
  pretending to have checked them.
- **Unsigned entries.** `signature`, `key_id` and `signature_scheme` are all `NULL`. Every
  v1 entry is unsigned, because signing did not exist when they were written; so is anything
  written by an instance with no signing key configured. The three columns are always all
  null or all present.
- **Redacted payloads.** `payload` is `NULL` while `payload_hash` is present. The owner
  removed the payload after the fact. The entry still verifies through the stored
  `payload_hash`; only the check of §6.1 is skipped, because there is nothing left to hash.

---

## 3. Value encodings

Every value that enters a preimage is first turned into a JSON value by these rules.

| Kind | Encoding | Example |
|---|---|---|
| UUID | Lowercase canonical text form with hyphens, as a JSON string | `"00000000-0000-7000-8000-000000000001"` |
| Integer | JSON number, no quotes, no sign for positives, no leading zeros | `5` |
| Boolean | `true` / `false` | `true` |
| Null | The literal `null` | `null` |
| Empty string | A JSON string of length zero | `""` |
| Text | JSON string, escaped per §4.3 | `"conduct.message.received"` |
| Timestamp | JSON string, per scheme — §3.1 | `"2026-09-11T10:00:00.000000Z"` |
| Hash | JSON string of 64 lowercase hex characters | `"417b2e…"` |

`null` and `""` are different values and produce different hashes. The vectors in §10.4 pin
that difference.

### 3.1 Timestamps

`drained_at` is stored as `timestamptz` with microsecond resolution, in UTC. The two schemes
serialise it differently, and this is the single largest difference between them.

**v2 — fixed precision.** ISO-8601, always exactly six fractional digits, always a trailing
`Z`:

```
2026-09-11T10:00:00.000000Z
2026-09-11T10:00:00.123456Z
```

An implementation reads the stored value in UTC and formats
`uuuu-MM-ddTHH:mm:ss.SSSSSSZ`. Nothing is omitted and nothing is inferred.

**v1 — variable precision.** ISO-8601 with the **fewest fractional digits from {0, 3, 6, 9}
that represent the value exactly**, and a trailing `Z`:

| Stored value | v1 serialisation |
|---|---|
| `10:00:00.000000` | `2026-09-11T10:00:00Z` |
| `10:00:00.100000` | `2026-09-11T10:00:00.100Z` |
| `10:00:00.500000` | `2026-09-11T10:00:00.500Z` |
| `10:00:00.123456` | `2026-09-11T10:00:00.123456Z` |
| `10:00:00.000001` | `2026-09-11T10:00:00.000001Z` |

The 9-digit case cannot occur for `drained_at`: the value is truncated to microseconds
before it is hashed and stored, so the two always agree. Seconds are always written, even
when zero.

This variability is the defect v2 corrects: the same instant could be written two ways
depending on its value, which an external implementation cannot reproduce without knowing
this rule. That is why the rule is written out here rather than referred to.

---

## 4. Canonical JSON

Both preimages are canonical JSON documents. The form is deterministic: one document, one
byte sequence. It is computed from the JSON *values*, never from the database's own `jsonb`
text output, whose key order, spacing and number formatting are not contractual.

### 4.1 Structure

- An object is `{`, then its members separated by `,`, then `}`. A member is the canonical
  string form of its key, then `:`, then the canonical form of its value.
- An array is `[`, then its elements separated by `,`, then `]`.
- **No whitespace anywhere** — not between tokens, not after `:` or `,`.
- An empty object is `{}`; an empty array is `[]`.

### 4.2 Member order

Object members are sorted by key, ascending, comparing the keys as **sequences of UTF-16
code units** — the same ordering RFC 8785 (JSON Canonicalization Scheme) specifies, and the
same one JavaScript and Java produce natively.

This matters only for keys containing characters outside the Basic Multilingual Plane. For
such a key, UTF-16 code unit order and Unicode code point order **disagree**: a character
like U+1F600 begins with the surrogate `0xD83D`, which sorts before a BMP character like
U+FFFD, whereas its code point is far higher. Sort by code units.

Under v2, keys are normalised (§4.4) **before** they are sorted, so the order is the order
of the forms actually written.

Duplicate keys cannot occur: the source is a JSON object that has already been parsed.

### 4.3 Strings

A string is `"`, then each character encoded as follows, then `"`:

| Character | Output |
|---|---|
| `"` | `\"` |
| `\` | `\\` |
| U+0008 backspace | `\b` |
| U+000C form feed | `\f` |
| U+000A line feed | `\n` |
| U+000D carriage return | `\r` |
| U+0009 tab | `\t` |
| Any other below U+0020 | `\u` followed by four **lowercase** hex digits, e.g. `` |
| Everything else | The character itself, encoded as UTF-8 |

Note what is **not** escaped: `/` is written literally, U+007F (delete) is written literally,
and all non-ASCII characters are written literally as UTF-8. No `\uXXXX` escape is emitted
above U+001F.

The finished document is encoded as UTF-8 before hashing.

### 4.4 Unicode normalisation

Under **v2**, every string — both object keys and string values — is normalised to Unicode
**NFC** before being escaped. Two byte sequences with identical meaning therefore produce the
same hash.

Under **v1**, no normalisation is applied; strings are hashed as stored.

For text already in NFC — which includes all ASCII — the two schemes produce identical
output. §10.3 pins both the agreeing and the diverging case.

### 4.5 Numbers

A number is written as its shortest exact decimal form, without an exponent and without
trailing fractional zeros. Concretely, this is the result of parsing the number as an
arbitrary-precision decimal, stripping trailing zeros, and formatting it in plain (non
scientific) notation.

Consequences worth stating: `1`, `1.0` and `1.00` all serialise to `1` and are
indistinguishable after hashing; `100` serialises to `100`, never `1E+2`; the sign is written
only for negative numbers.

Values that are not finite (NaN, infinities) are not representable and are rejected.

### 4.6 Permitted value types

Object, array, string, number, boolean and null. Nothing else appears in an audit payload —
these are exactly the types a JSON document round-trips through `jsonb`.

---

## 5. The entry hash

```
entry_hash = lowercase_hex( SHA-256( UTF-8( canonical_json( preimage ) ) ) )
```

64 lowercase hexadecimal characters. The preimage is an **explicit** object: a verifier
builds it from named fields, never by serialising a database row.

### 5.1 The v1 preimage — eight fields

| Key | Value |
|---|---|
| `drained_at` | The timestamp, v1 form (§3.1) |
| `event_type` | The entry's event type |
| `outbox_id` | The outbox id, as a UUID string |
| `payload_hash` | The stored `payload_hash` |
| `prev_hash` | The stored `prev_hash` |
| `scheme` | The literal string `v1` |
| `sequence_number` | The sequence number, as a JSON number |
| `tenant_id` | The tenant id, as a UUID string |

No NFC normalisation (§4.4).

### 5.2 The v2 preimage — the same eight, plus `id`

| Key | Value |
|---|---|
| `id` | The entry's own primary key, as a UUID string |
| *(the eight above)* | As in §5.1, except `drained_at` in v2 form and `scheme` = `v2` |

NFC normalisation applies (§4.4).

Since members are sorted (§4.2), `id` lands between `event_type` and `outbox_id`.

### 5.3 What changed in v2, and why

1. **`id` enters the preimage.** Under v1 it was the only column outside the hash, so it
   could be altered without that alteration being detectable.
2. **Fixed-precision timestamps.** See §3.1. Under v1 the same instant could serialise two
   ways, which an independent implementation cannot reproduce without extra knowledge.
3. **NFC normalisation.** Under v1, two strings with identical meaning but different byte
   sequences produce different hashes.

The scheme identifier is inside the preimage *and* stored in the `hash_scheme` column, so a
v1 and a v2 hash of the same entry can never be confused for one another.

### 5.4 What is never in the preimage

The **raw payload** — only its hash, which is what lets an entry still verify after the
payload has been redacted. The **signature**, `key_id` and `signature_scheme` — signing is
downstream of the entry hash, so adding a signature re-hashes nothing.

---

## 6. The payload hash

```
payload_hash = lowercase_hex( SHA-256( UTF-8( canonical_json( payload ) ) ) )
```

The payload is a JSON object. It is canonicalised by the rules of §4 under the **entry's own
scheme**: a v1 entry's payload hash is computed without NFC normalisation, a v2 entry's with
it. Applying v2 rules to a v1 entry would declare honest entries broken.

### 6.1 When the payload is present

If `payload` is not `NULL`, recomputing its hash must reproduce the stored `payload_hash`.

### 6.2 When the payload has been redacted

If `payload` is `NULL`, this check is skipped and the stored `payload_hash` is used as-is for
the entry hash of §5. The chain still verifies. This is a property of the construction: the
chain commits to the hash of the payload, never to the payload itself.

---

## 7. Chaining

### 7.1 The genesis hash

Each tenant's chain starts from a hash derived from the tenant id:

```
genesis = lowercase_hex( SHA-256( UTF-8( "cauce-audit-genesis:v1:" + tenant_id ) ) )
```

where `tenant_id` is the lowercase canonical UUID text form. The concatenation is a plain
string, not JSON.

The prefix is **frozen at the `v1` spelling for both schemes and is not versioned**. It is a
tenant-binding domain separator, not a preimage: it contains no timestamp and no free text,
so none of the v2 corrections apply. A segment transplanted from another tenant's chain
therefore fails at the first entry.

### 7.2 Linking

Walk a tenant's entries in ascending `sequence_number`:

1. Sequence numbers must be contiguous starting at 1. A gap means an entry that was chained
   is no longer present.
2. Skip pre-chain rows (§2.1) while they form the leading prefix; count them. A pre-chain row
   appearing after a chained row is a defect.
3. For the first chained entry, `prev_hash` must equal the genesis hash of §7.1.
4. For every later chained entry, `prev_hash` must equal the previous chained entry's
   `entry_hash`.
5. Recompute `entry_hash` per §5 under the entry's own `hash_scheme` and compare.

Because each entry hash commits to the previous entry's hash, the last entry's `entry_hash`
is a compact commitment to the entire chain up to that point. That value is the chain
**head**, and it is what an external anchor would publish.

---

## 8. Signatures

An entry may carry a signature. Scheme `ed25519-v1` is the only one defined.

### 8.1 The signature preimage

The signed bytes are the UTF-8 encoding of:

```
cauce-audit-signature:v1:<key_id>:<signature_scheme>:<entry_hash>
```

with no whitespace and no trailing newline. For example:

```
cauce-audit-signature:v1:526b1a687e2f4293:ed25519-v1:c532e13eccdd3d9c2f727840b0340c253e91939329e64319aad9443546743a89
```

The signature covers the entry's **chained** hash, which already commits to the whole prefix
of the chain, so signing it commits to that prefix too.

Three things are bound inside the signed bytes rather than sitting beside them: the domain
separation prefix, so a signature made here cannot be valid in another context signed by the
same key; the `key_id`, so a signature cannot be moved to a row that names a different key;
and the signature scheme, so a future scheme's signatures cannot be replayed as this one's.

The `v1` in the prefix versions **the shape of this string** and is independent of both the
hash scheme and the signature scheme.

### 8.2 Algorithm and encoding

Ed25519 (RFC 8032), as specified — no pre-hashing. The signature is 64 bytes, stored
base64-encoded with padding, in the `signature` column.

### 8.3 Verifying

1. If `signature`, `key_id` and `signature_scheme` are all `NULL`, the entry is unsigned.
   That is not a defect (§2.1).
2. If some but not all three are present, report it as an **invalid signature** (§9,
   `SIGNATURE_INVALID`): something wrote an inconsistent row, and it is not what was signed.
3. If `signature_scheme` is not one your implementation knows, you cannot check that entry.
   Report it as unchecked, not as wrong.
4. Look up `key_id` in the registry (§8.4). If it is absent, you cannot check that entry.
   Report it as unchecked, not as wrong.
5. Otherwise verify the signature of §8.1 against the public key. A failure here means the
   entry is not what was signed.

The distinction in steps 3 and 4 matters: "I could not check this" is not "this failed", and
collapsing them would report an incomplete registry as an altered chain.

### 8.4 The key registry

The `key_id` → public key mapping is published **outside the database**, as a JSON file. This
placement is the point: the chain alone detects alteration by someone who cannot recompute
it, and signatures exist to detect alteration by someone who can — an actor with database
access. If the public keys lived in the same database, that actor could substitute one and
have their forgeries verify.

Each operator publishes their own registry. There is no central authority; this is a format.

```json
{
  "registry_version": 1,
  "keys": [
    {
      "key_id": "526b1a687e2f4293",
      "algorithm": "ed25519",
      "public_key": "MCowBQYDK2VwAyEAvW00gfFNBbKDKfKbAky7KUqJZg2PjbnHru+jG0xBwWE=",
      "activated_at": "2026-09-11T00:00:00Z",
      "retired_at": null,
      "compromised_at": null
    }
  ]
}
```

The root is an **object** with a `keys` array; a bare array is not the format.

| Field | Required | Meaning |
|---|---|---|
| `key_id` | yes | Must equal the value derived from `public_key` per §8.5 |
| `algorithm` | yes | `ed25519`, matched case-insensitively |
| `public_key` | yes | Base64 SubjectPublicKeyInfo DER — the body of a PEM `BEGIN PUBLIC KEY` block |
| `activated_at` | no | ISO-8601 instant, or `null`, or absent. When the key began signing |
| `retired_at` | no | When the **private** key stopped signing. Never means the public key goes away |
| `compromised_at` | no | When the key was found compromised |
| `registry_version` | no | Present in the format; **not currently validated** by Cauce's loader |

Semantics an implementer must not get wrong:

- **Entries are only ever added.** A public key is never removed: everything it signed must
  stay verifiable, and removing it would make honest entries uncheckable.
- **`retired_at` is about the private half.** A retired key's entries verify exactly as
  before.
- **`compromised_at` is reported, not enforced.** Entries signed by a key later marked
  compromised still verify — the signature is still correct. What a later compromise means
  for them is a judgement for whoever is auditing, not a computation. Note the honest limit:
  without a reliable external timestamp, entries signed *before* the compromise cannot be
  distinguished from entries signed after it.
- A `key_id` must not appear twice.

### 8.5 Deriving the `key_id`

```
key_id = lowercase_hex( first 8 bytes of SHA-256( SubjectPublicKeyInfo DER ) )
```

Exactly 16 hexadecimal characters. The input is the DER encoding of the public key as
RFC 8410 SubjectPublicKeyInfo — precisely the bytes a PEM `BEGIN PUBLIC KEY` block
base64-encodes — so the id is reproducible from the published key alone.

Truncating to 64 bits is deliberate. The `key_id` is a **lookup identifier**, not a
cryptographic commitment: nothing is believed because two ids match, and a collision would
only cause the wrong key to be tried, which fails verification. It is a digest of the public
key, so publishing it on every row discloses nothing.

---

## 9. Verification

Walk one tenant's entries in ascending `sequence_number`, keeping the expected next sequence
(starting at 1), the expected `prev_hash` (starting at the genesis hash of §7.1), and a tally
of signature outcomes. For each entry apply the checks below **in this order**, and stop at
the first one that fails. The order is normative: when an entry is wrong in more than one
way, the kind reported is the first in this list.

| # | Check | On failure |
|---|---|---|
| 1 | `sequence_number` equals the expected next sequence | `ENTRY_MISSING` at the sequence found |
| 2 | If `entry_hash` is `NULL` (a pre-chain row, §2.1): permitted only while no chained entry has been seen; count it and continue with the next entry | `UNCHAINED_ENTRY_OUT_OF_ORDER` |
| 3 | `hash_scheme` is one this implementation knows (`v1`, `v2`) | **Stop** — not a defect. Everything from this sequence on is unchecked (see below) |
| 4 | `payload_hash` and `prev_hash` are both present | `ENTRY_MALFORMED` |
| 5 | If `payload` is present, its hash under the entry's scheme (§6) equals the stored `payload_hash` | `ENTRY_ALTERED` |
| 6 | `prev_hash` equals the expected previous hash | `LINK_BROKEN` |
| 7 | The recomputed `entry_hash` (§5) equals the stored one | `ENTRY_ALTERED` |
| 8 | The signature, per §8.3: unsigned is counted; a scheme or key this implementation cannot check is counted as unverifiable and the walk continues; a partial triple, or a signature that does not verify, fails | `SIGNATURE_INVALID` |

After check 8 passes, the expected previous hash becomes this entry's `entry_hash` and the
expected sequence advances.

### 9.1 Break kinds

The vocabulary an implementation reports, and what each means:

| Kind | Meaning |
|---|---|
| `ENTRY_ALTERED` | A stored entry's content or metadata no longer matches a hash recorded when it was chained (its payload hash, or its entry hash) |
| `SIGNATURE_INVALID` | The entry's signature does not verify against the key its `key_id` names, or the signature columns are partially populated. The entry is not what was signed |
| `LINK_BROKEN` | `prev_hash` is not the previous entry's `entry_hash`: the chain was cut or reordered |
| `ENTRY_MISSING` | The per-tenant sequence skips a number: a chained entry is no longer present |
| `UNCHAINED_ENTRY_OUT_OF_ORDER` | A pre-chain row appears after a chained entry |
| `ENTRY_MALFORMED` | A chained entry is missing `payload_hash` or `prev_hash` |

### 9.2 Outcomes

Three outcomes are meaningful, and keeping them apart is the point. Precedence, when more
than one applies: **BROKEN** over **UNVERIFIABLE** over **VALID**.

- **BROKEN.** A check failed. Report the **first** sequence number at which it happened and
  the kind from §9.1. Nothing after it is evaluated.
- **UNVERIFIABLE.** No check failed, but part of the chain could not be looked at: the walk
  stopped at check 3 on a hash scheme you do not implement (report that sequence), or one or
  more signatures could not be checked at check 8 (report which `key_id`s were missing).
  Neither good nor bad — no answer for that part. Report what was checked, what was not, and
  why.
- **VALID.** Every chained entry recomputed and linked, and every signature that could be
  checked verified.

An entry whose hash scheme you do not implement stops the walk: entries after it have not
been examined, and reporting them as consistent would be a claim you cannot support.

**What recomputation alone does not establish.** An actor who holds the signing key can
rewrite an entry, recompute every hash from that point onward, re-sign, and rewrite the head.
Recomputation and signature checking cannot distinguish that from an honest chain. Entries
that carry no signature are covered by recomputation only, which cannot distinguish such a
rewrite by an actor with database access either. And a chain shortened to a consistent
earlier state cannot be told apart from one that was never longer, unless you hold a head
recorded earlier from outside the database.

---

## 10. Test vectors

Every value below was produced by the shipped implementation and reproduced independently
from this document's prose. The repository pins them twice: `AuditFormatSpecVectorsTest`
asserts them against the Java implementation, and the reference verifier under
[`tools/audit-chain-verifier/`](../../tools/audit-chain-verifier/README.md) — written
against this document and importing no Cauce code — recomputes them in its `--self-test`.
A change to the format that this document does not describe fails both.

### 10.1 Genesis hash

```
tenant_id  00000000-0000-7000-8000-000000000001
preimage   cauce-audit-genesis:v1:00000000-0000-7000-8000-000000000001
genesis    0aaeefb6e95ffe2c9f1991f9964cbe1b7b4c200826d6d5c4149284a9be767fe5
```

### 10.2 Entry hash, both schemes

One entry, the same fields under each scheme. `drained_at` is a whole second on purpose: it
is the case the two schemes serialise differently.

```
id               00000000-0000-7000-8000-0000000000e3
tenant_id        00000000-0000-7000-8000-000000000001
sequence_number  5
outbox_id        00000000-0000-7000-8000-0000000000a2
event_type       conduct.message.received
drained_at       2026-09-11T10:00:00Z  (zero microseconds)
payload_hash     pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp
prev_hash        qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
```

**v1** — preimage, as a single line with no whitespace:

```
{"drained_at":"2026-09-11T10:00:00Z","event_type":"conduct.message.received","outbox_id":"00000000-0000-7000-8000-0000000000a2","payload_hash":"pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp","prev_hash":"qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq","scheme":"v1","sequence_number":5,"tenant_id":"00000000-0000-7000-8000-000000000001"}
```

```
entry_hash  417b2ecfa75612c53dca948f544f7309da10bf1031646485473d6e00a6054875
```

**v2** — preimage:

```
{"drained_at":"2026-09-11T10:00:00.000000Z","event_type":"conduct.message.received","id":"00000000-0000-7000-8000-0000000000e3","outbox_id":"00000000-0000-7000-8000-0000000000a2","payload_hash":"pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp","prev_hash":"qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq","scheme":"v2","sequence_number":5,"tenant_id":"00000000-0000-7000-8000-000000000001"}
```

```
entry_hash  c532e13eccdd3d9c2f727840b0340c253e91939329e64319aad9443546743a89
```

The two differ in exactly three places: the timestamp's fraction, the presence of `id`, and
the value of `scheme`.

### 10.3 Payload hash, and where the schemes diverge

An ASCII payload. Both schemes agree, because the text is already in NFC:

```
payload  {"content_hash":"3f2b1c","finish_reason":"STOP","rounds":2}
v1       6b9695c971ed3d84721505400296857d0ca8cbb7eee671f38039fc459a73a57b
v2       6b9695c971ed3d84721505400296857d0ca8cbb7eee671f38039fc459a73a57b
```

The same name in two Unicode forms — `jos` + U+00E9 (NFC) against `jose` + U+0301 (NFD).
Under v1 they are different documents; under v2 they are the same one:

```
{"actor":"josé"}  (NFD)   v1  68e3c767fabfd41456e91b48f91429369453d510ebfa7a153adaa6fe5063c566
{"actor":"josé"}   (NFC)   v1  d02fa0fb31ba5fa261947ee7c604b9487ef3aa85778d4fd823c32f5894892a8a
{"actor":"josé"}  (NFD)   v2  d02fa0fb31ba5fa261947ee7c604b9487ef3aa85778d4fd823c32f5894892a8a
{"actor":"josé"}   (NFC)   v2  d02fa0fb31ba5fa261947ee7c604b9487ef3aa85778d4fd823c32f5894892a8a
```

The escapes above name the code points; the documents themselves contain those characters
literally, not the escape sequences (§4.3).

### 10.4 Encoding corner cases

Each line is the SHA-256 of the canonical document shown, which is also the `payload_hash` of
a payload with that content:

```
{"n":1}      2bfd14f43d17fc7cea24e0917a8879b4b2f880b8baeec1b9d90fbaad655e71bd
{"n":1.0}    2bfd14f43d17fc7cea24e0917a8879b4b2f880b8baeec1b9d90fbaad655e71bd   (identical: §4.5)
{"n":null}   5b4da02351c1c20974b216a5e3a4edb59ac51cbda6be3e9d82952b4f5beea463
{"n":""}     6b760ea9d4ac65941818f2a3a9afc102217be814b3a4684c685f38be1054a3d2
{}           44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a
```

`1` and `1.0` collide by construction. `null` and `""` do not.

### 10.5 Signature

A signature over the v2 entry hash of §10.2, made with a key generated for this document and
used nowhere else. Its private half was discarded and never existed in any instance — the
reproducible direction here is verification, which is the direction a verifier needs.

```
key_id             526b1a687e2f4293
public_key         MCowBQYDK2VwAyEAvW00gfFNBbKDKfKbAky7KUqJZg2PjbnHru+jG0xBwWE=
signature_scheme   ed25519-v1
signed bytes       cauce-audit-signature:v1:526b1a687e2f4293:ed25519-v1:c532e13eccdd3d9c2f727840b0340c253e91939329e64319aad9443546743a89
signature          rU8G/ugMySdy/2PaiWtZMSmK2Dzzf7fgG5UbM1Ph2YySyCTO4DNdq5wSgC5xhQebxFLGGnSiTHdr/1V93r3vAA==
```

Deriving `key_id` from `public_key` by §8.5 yields `526b1a687e2f4293`, which is how an
implementation checks that a registry names the key it actually contains.

---

## 11. Versioning

What is frozen and will not change:

- The v1 rules, for as long as any v1 entry exists — which is forever, since entries are
  never rewritten.
- The genesis prefix `cauce-audit-genesis:v1:`, for every hash scheme.
- The signature preimage prefix `cauce-audit-signature:v1:`.
- Every vector in §10.

How a future scheme would arrive: a new value of `hash_scheme` (say `v3`), written only on
new entries, with existing entries keeping theirs. A verifier that does not implement `v3`
reports those entries as unchecked (§9) rather than as defective. The same applies to a new
`signature_scheme`. Nothing is ever re-hashed or re-signed; doing so would be rewriting the
history the ledger exists to record.
