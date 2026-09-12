#!/usr/bin/env node
// Reference verifier for a Cauce audit chain.
//
// Written against docs/spec/audit-chain-format.md and nothing else. It imports no Cauce
// code and no third-party package: everything it needs — SHA-256, Ed25519, Unicode NFC,
// UTF-16 string ordering — is in Node's standard library (Node 18 or later). It exists so
// that "the screen says the chain is fine" can be replaced by "run this yourself".
//
// Section numbers in comments (§4.2, §9 …) refer to the specification. Where this program
// and the specification disagree, the specification wins and this program has a bug.
//
//   node verify.mjs <dump.json> <registry.json> [--json]
//   node verify.mjs --self-test
//
// Exit codes: 0 VALID · 1 BROKEN · 2 UNVERIFIABLE · 3 could not run (bad input, usage).

import { createHash, createPublicKey, verify as cryptoVerify } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// ─────────────────────────────────────────────────────────────── JSON reader
//
// A small strict JSON reader instead of JSON.parse, for one reason: §4.5 defines the
// canonical form of a number from its DECIMAL TEXT, and JSON.parse discards that text when
// it converts to a double. Numbers come out of this reader as { num: "<text>" }; everything
// else is the plain JavaScript value. Objects keep their keys in a Map so nothing about
// ordering is assumed before §4.2 sorts them.

class JsonReader {
  constructor(text) { this.t = text; this.i = 0; }

  fail(msg) { throw new Error(`invalid JSON at offset ${this.i}: ${msg}`); }

  ws() { while (this.i < this.t.length && ' \t\n\r'.includes(this.t[this.i])) this.i++; }

  read() {
    this.ws();
    const v = this.value();
    this.ws();
    if (this.i !== this.t.length) this.fail('trailing characters');
    return v;
  }

  value() {
    this.ws();
    const c = this.t[this.i];
    if (c === '{') return this.object();
    if (c === '[') return this.array();
    if (c === '"') return this.string();
    if (c === 't') return this.literal('true', true);
    if (c === 'f') return this.literal('false', false);
    if (c === 'n') return this.literal('null', null);
    if (c === '-' || (c >= '0' && c <= '9')) return this.number();
    this.fail(`unexpected character ${JSON.stringify(c)}`);
  }

  literal(word, v) {
    if (this.t.startsWith(word, this.i)) { this.i += word.length; return v; }
    this.fail(`expected ${word}`);
  }

  object() {
    const m = new Map();
    this.i++; // {
    this.ws();
    if (this.t[this.i] === '}') { this.i++; return m; }
    for (;;) {
      this.ws();
      if (this.t[this.i] !== '"') this.fail('expected string key');
      const k = this.string();
      if (m.has(k)) this.fail(`duplicate key ${JSON.stringify(k)}`);
      this.ws();
      if (this.t[this.i] !== ':') this.fail('expected :');
      this.i++;
      m.set(k, this.value());
      this.ws();
      if (this.t[this.i] === ',') { this.i++; continue; }
      if (this.t[this.i] === '}') { this.i++; return m; }
      this.fail('expected , or }');
    }
  }

  array() {
    const a = [];
    this.i++; // [
    this.ws();
    if (this.t[this.i] === ']') { this.i++; return a; }
    for (;;) {
      a.push(this.value());
      this.ws();
      if (this.t[this.i] === ',') { this.i++; continue; }
      if (this.t[this.i] === ']') { this.i++; return a; }
      this.fail('expected , or ]');
    }
  }

  string() {
    this.i++; // opening quote
    let out = '';
    for (;;) {
      if (this.i >= this.t.length) this.fail('unterminated string');
      const c = this.t[this.i++];
      if (c === '"') return out;
      if (c !== '\\') { out += c; continue; }
      const e = this.t[this.i++];
      switch (e) {
        case '"': out += '"'; break;
        case '\\': out += '\\'; break;
        case '/': out += '/'; break;
        case 'b': out += '\b'; break;
        case 'f': out += '\f'; break;
        case 'n': out += '\n'; break;
        case 'r': out += '\r'; break;
        case 't': out += '\t'; break;
        case 'u': {
          const hex = this.t.substr(this.i, 4);
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) this.fail('bad \\u escape');
          this.i += 4;
          out += String.fromCharCode(parseInt(hex, 16)); // surrogate halves recombine naturally
          break;
        }
        default: this.fail(`bad escape \\${e}`);
      }
    }
  }

  number() {
    const m = /^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?/.exec(this.t.slice(this.i));
    if (!m) this.fail('malformed number');
    this.i += m[0].length;
    return { num: m[0] };
  }
}

const isNum = (v) => v !== null && typeof v === 'object' && !Array.isArray(v) && !(v instanceof Map) && 'num' in v;

/** Plain JavaScript number from a reader value (for sequence numbers and counts). */
function toNumber(v, what) {
  if (!isNum(v)) throw new Error(`${what} must be a number`);
  const n = Number(v.num);
  if (!Number.isSafeInteger(n)) throw new Error(`${what} is not a safe integer`);
  return n;
}

// ─────────────────────────────────────────────────────────── canonical JSON (§4)

/**
 * §4.5: the number as its shortest exact decimal, no exponent, no trailing fractional
 * zeros — the plain form of the arbitrary-precision decimal the text denotes. Done on the
 * digits, so nothing is rounded.
 */
function canonicalNumber(text) {
  const m = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(text);
  if (!m) throw new Error(`not a JSON number: ${text}`);
  let digits = m[2] + (m[3] ?? '');
  let exp = (m[4] ? parseInt(m[4], 10) : 0) - (m[3] ? m[3].length : 0);
  digits = digits.replace(/^0+/, '');
  if (digits === '') return '0';
  while (digits.endsWith('0')) { digits = digits.slice(0, -1); exp += 1; }
  let plain;
  if (exp >= 0) {
    plain = digits + '0'.repeat(exp);
  } else {
    const k = -exp;
    plain = digits.length > k
      ? digits.slice(0, digits.length - k) + '.' + digits.slice(digits.length - k)
      : '0.' + '0'.repeat(k - digits.length) + digits;
  }
  return (m[1] === '-' ? '-' : '') + plain;
}

/** §4.3: the seven named escapes, \u00xx (lowercase) below U+0020, everything else literal. */
function canonicalString(s) {
  let out = '"';
  for (const ch of s) {
    switch (ch) {
      case '"': out += '\\"'; break;
      case '\\': out += '\\\\'; break;
      case '\b': out += '\\b'; break;
      case '\f': out += '\\f'; break;
      case '\n': out += '\\n'; break;
      case '\r': out += '\\r'; break;
      case '\t': out += '\\t'; break;
      default: {
        const cp = ch.codePointAt(0);
        out += cp < 0x20 ? '\\u' + cp.toString(16).padStart(4, '0') : ch;
      }
    }
  }
  return out + '"';
}

/**
 * §4: canonical JSON of a reader value. `normalize` is the v2 behaviour of §4.4 — NFC on
 * every key and string value, applied to keys BEFORE sorting.
 *
 * Member order (§4.2) is by UTF-16 code units. That is exactly what JavaScript's default
 * string comparison does, so Array.prototype.sort() with no comparator is the spec's order.
 */
function canonical(v, normalize) {
  if (v === null) return 'null';
  if (v === true) return 'true';
  if (v === false) return 'false';
  if (typeof v === 'string') return canonicalString(normalize ? v.normalize('NFC') : v);
  if (isNum(v)) return canonicalNumber(v.num);
  if (Array.isArray(v)) return '[' + v.map((e) => canonical(e, normalize)).join(',') + ']';
  if (v instanceof Map) {
    const members = [...v].map(([k, val]) => [normalize ? k.normalize('NFC') : k, val]);
    members.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
    return '{' + members.map(([k, val]) => canonicalString(k) + ':' + canonical(val, normalize)).join(',') + '}';
  }
  throw new Error(`value type is not canonicalizable`);
}

const sha256hex = (text) => createHash('sha256').update(text, 'utf8').digest('hex');

// ─────────────────────────────────────────────────────────── timestamps (§3.1)

/** Parses "YYYY-MM-DDTHH:MM:SS[.f{1,9}]Z" into its date-time text and its nanoseconds. */
function parseInstant(s) {
  const m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(s);
  if (!m) throw new Error(`drained_at is not an ISO-8601 UTC instant: ${s}`);
  const nanos = m[2] ? parseInt(m[2].padEnd(9, '0'), 10) : 0;
  return { dateTime: m[1], nanos };
}

/** §3.1 v2: exactly six fractional digits. The stored value has microsecond resolution. */
function drainedAtV2({ dateTime, nanos }) {
  if (nanos % 1000 !== 0) throw new Error('drained_at carries sub-microsecond digits');
  return `${dateTime}.${String(nanos / 1000).padStart(6, '0')}Z`;
}

/** §3.1 v1: the fewest of {0, 3, 6, 9} fractional digits that represent the value exactly. */
function drainedAtV1({ dateTime, nanos }) {
  if (nanos === 0) return `${dateTime}Z`;
  if (nanos % 1_000_000 === 0) return `${dateTime}.${String(nanos / 1_000_000).padStart(3, '0')}Z`;
  if (nanos % 1_000 === 0) return `${dateTime}.${String(nanos / 1_000).padStart(6, '0')}Z`;
  return `${dateTime}.${String(nanos).padStart(9, '0')}Z`;
}

// ─────────────────────────────────────────────────────── the format (§5, §6, §7, §8)

const SCHEMES = new Set(['v1', 'v2']);

/** §5: the entry hash over the explicit preimage. Returns the hash and the preimage text. */
function entryHash(scheme, e) {
  const v2 = scheme === 'v2';
  const instant = parseInstant(e.drained_at);
  const preimage = new Map([
    ['drained_at', v2 ? drainedAtV2(instant) : drainedAtV1(instant)],
    ['event_type', e.event_type],
    ['outbox_id', e.outbox_id],
    ['payload_hash', e.payload_hash],
    ['prev_hash', e.prev_hash],
    ['scheme', scheme],
    ['sequence_number', { num: String(e.sequence_number) }],
    ['tenant_id', e.tenant_id],
  ]);
  if (v2) preimage.set('id', e.id);
  const text = canonical(preimage, v2);
  return { hash: sha256hex(text), preimage: text };
}

/** §6: the payload hash under the entry's scheme. */
const payloadHash = (scheme, payload) => sha256hex(canonical(payload, scheme === 'v2'));

/** §7.1: the genesis hash — a plain string, frozen at the v1 spelling for every scheme. */
const genesisHash = (tenantId) => sha256hex('cauce-audit-genesis:v1:' + tenantId);

/** §8.1: the signed bytes. */
const signedBytes = (keyId, scheme, hash) =>
  Buffer.from(`cauce-audit-signature:v1:${keyId}:${scheme}:${hash}`, 'utf8');

/** §8.5: the key id from the SubjectPublicKeyInfo DER. */
const keyIdOf = (spkiDer) => createHash('sha256').update(spkiDer).digest('hex').slice(0, 16);

const SIGNATURE_SCHEMES = new Set(['ed25519-v1']);

// ─────────────────────────────────────────────────────────── key registry (§8.4)

function loadRegistry(reader) {
  if (!(reader instanceof Map)) throw new Error('registry: root must be an object');
  const keys = reader.get('keys');
  if (!Array.isArray(keys)) throw new Error('registry: "keys" must be an array');
  const byId = new Map();
  for (const k of keys) {
    if (!(k instanceof Map)) throw new Error('registry: each key must be an object');
    const keyId = k.get('key_id');
    const algorithm = k.get('algorithm');
    const publicKey = k.get('public_key');
    for (const [name, v] of [['key_id', keyId], ['algorithm', algorithm], ['public_key', publicKey]]) {
      if (typeof v !== 'string' || v === '') throw new Error(`registry: "${name}" is required`);
    }
    if (algorithm.toLowerCase() !== 'ed25519') throw new Error(`registry: unsupported algorithm ${algorithm}`);
    const der = Buffer.from(publicKey, 'base64');
    const derived = keyIdOf(der);
    if (derived !== keyId) {
      throw new Error(`registry: key_id ${keyId} does not match its public key (derived ${derived})`);
    }
    if (byId.has(keyId)) throw new Error(`registry: key_id ${keyId} declared twice`);
    const key = createPublicKey({ key: der, format: 'der', type: 'spki' });
    if (key.asymmetricKeyType !== 'ed25519') throw new Error(`registry: ${keyId} is not an Ed25519 key`);
    const instant = (field) => {
      const v = k.get(field);
      if (v === undefined || v === null) return null;
      if (typeof v !== 'string' || Number.isNaN(Date.parse(v))) throw new Error(`registry: "${field}" is not an instant`);
      return v;
    };
    byId.set(keyId, { key, activatedAt: instant('activated_at'), retiredAt: instant('retired_at'), compromisedAt: instant('compromised_at') });
  }
  return byId;
}

// ─────────────────────────────────────────────────────────────── the dump

/** Reads an entry object from the dump into a plain record; payload stays a reader value. */
function readEntry(m, index) {
  if (!(m instanceof Map)) throw new Error(`entries[${index}] must be an object`);
  const str = (name, optional = false) => {
    const v = m.get(name);
    if (v === undefined || v === null) {
      if (optional) return null;
      throw new Error(`entries[${index}].${name} is required`);
    }
    if (typeof v !== 'string') throw new Error(`entries[${index}].${name} must be a string`);
    return v;
  };
  return {
    id: str('id'),
    sequence_number: toNumber(m.get('sequence_number'), `entries[${index}].sequence_number`),
    outbox_id: str('outbox_id'),
    event_type: str('event_type'),
    payload: m.has('payload') ? m.get('payload') : null,
    drained_at: str('drained_at'),
    payload_hash: str('payload_hash', true),
    prev_hash: str('prev_hash', true),
    entry_hash: str('entry_hash', true),
    hash_scheme: str('hash_scheme', true),
    signature: str('signature', true),
    key_id: str('key_id', true),
    signature_scheme: str('signature_scheme', true),
  };
}

function readDump(reader) {
  if (!(reader instanceof Map)) throw new Error('dump: root must be an object');
  if (reader.get('format') !== 'cauce-audit-chain-dump/1') {
    throw new Error('dump: "format" must be "cauce-audit-chain-dump/1"');
  }
  const tenantId = reader.get('tenant_id');
  if (typeof tenantId !== 'string') throw new Error('dump: "tenant_id" is required');
  const entries = reader.get('entries');
  if (!Array.isArray(entries)) throw new Error('dump: "entries" must be an array');
  return { tenantId, entries: entries.map(readEntry) };
}

// ─────────────────────────────────────────────────────────── verification (§9)

/**
 * The walk of §9, check by check, in the order the specification makes normative. Returns
 * the same shape the API reports: a status, the first break if any, the counts, the head,
 * where the walk stopped if it did, and the signature tally.
 */
function verifyChain({ tenantId, entries }, registry) {
  const sorted = [...entries].sort((a, b) => a.sequence_number - b.sequence_number);
  let expectedSequence = 1;
  let expectedPrev = genesisHash(tenantId);
  let chainStarted = false;
  let chained = 0;
  let preChain = 0;
  let head = null;
  let unverifiableFrom = null;
  const signatures = { verified: 0, unsigned: 0, unverifiable: 0, missing_key_ids: [], compromised_key_ids: {} };

  const broken = (sequence, kind, detail) => ({
    status: 'BROKEN', first_break: { sequence_number: sequence, kind, detail },
    verified_entries: chained, pre_chain_entries: preChain, head, unverifiable_from_sequence: null, signatures,
  });

  for (const e of sorted) {
    const seq = e.sequence_number;
    // 1 — contiguous sequence
    if (seq !== expectedSequence) return broken(seq, 'ENTRY_MISSING', `expected sequence ${expectedSequence}`);
    expectedSequence += 1;
    // 2 — pre-chain rows only as a prefix
    if (e.entry_hash === null) {
      if (chainStarted) return broken(seq, 'UNCHAINED_ENTRY_OUT_OF_ORDER', 'pre-chain row after a chained entry');
      preChain += 1;
      continue;
    }
    chainStarted = true;
    // 3 — a scheme this implementation knows; otherwise stop, and say so
    if (!SCHEMES.has(e.hash_scheme)) { unverifiableFrom = seq; break; }
    // 4 — the chain columns are present
    if (e.payload_hash === null || e.prev_hash === null) return broken(seq, 'ENTRY_MALFORMED', 'payload_hash or prev_hash is missing');
    // 5 — the payload, if still present, hashes to what was recorded
    if (e.payload !== null) {
      const got = payloadHash(e.hash_scheme, e.payload);
      if (got !== e.payload_hash) return broken(seq, 'ENTRY_ALTERED', `payload hashes to ${got}, recorded ${e.payload_hash}`);
    }
    // 6 — the link
    if (e.prev_hash !== expectedPrev) return broken(seq, 'LINK_BROKEN', `prev_hash ${e.prev_hash} is not the previous entry hash ${expectedPrev}`);
    // 7 — the entry hash
    const { hash } = entryHash(e.hash_scheme, { ...e, tenant_id: tenantId });
    if (hash !== e.entry_hash) return broken(seq, 'ENTRY_ALTERED', `entry recomputes to ${hash}, recorded ${e.entry_hash}`);
    // 8 — the signature
    const present = [e.signature, e.key_id, e.signature_scheme].filter((x) => x !== null).length;
    if (present === 0) {
      signatures.unsigned += 1;
    } else if (present !== 3) {
      return broken(seq, 'SIGNATURE_INVALID', 'signature columns are partially populated');
    } else if (!SIGNATURE_SCHEMES.has(e.signature_scheme)) {
      signatures.unverifiable += 1;
    } else if (!registry.has(e.key_id)) {
      signatures.unverifiable += 1;
      if (!signatures.missing_key_ids.includes(e.key_id)) signatures.missing_key_ids.push(e.key_id);
    } else {
      const entry = registry.get(e.key_id);
      let ok = false;
      try {
        ok = cryptoVerify(null, signedBytes(e.key_id, e.signature_scheme, e.entry_hash), entry.key, Buffer.from(e.signature, 'base64'));
      } catch { ok = false; }
      if (!ok) return broken(seq, 'SIGNATURE_INVALID', `signature does not verify against key ${e.key_id}`);
      signatures.verified += 1;
      if (entry.compromisedAt !== null) {
        signatures.compromised_key_ids[e.key_id] = (signatures.compromised_key_ids[e.key_id] ?? 0) + 1;
      }
    }
    expectedPrev = e.entry_hash;
    head = { sequence_number: seq, entry_hash: e.entry_hash };
    chained += 1;
  }

  const status = unverifiableFrom !== null || signatures.unverifiable > 0 ? 'UNVERIFIABLE' : 'VALID';
  return { status, first_break: null, verified_entries: chained, pre_chain_entries: preChain, head, unverifiable_from_sequence: unverifiableFrom, signatures };
}

// ─────────────────────────────────────────────────────────────── reporting

function render(result) {
  const lines = [];
  lines.push(`status: ${result.status}`);
  if (result.first_break) {
    const b = result.first_break;
    lines.push(`first break: sequence ${b.sequence_number} — ${b.kind}`);
    lines.push(`  ${b.detail}`);
  }
  lines.push(`entries verified: ${result.verified_entries}`);
  lines.push(`pre-chain entries: ${result.pre_chain_entries} (written before chaining existed; not checked, not a defect)`);
  if (result.unverifiable_from_sequence !== null) {
    lines.push(`unverifiable from sequence ${result.unverifiable_from_sequence}: that entry uses a hash scheme this verifier does not implement; nothing after it was examined`);
  }
  lines.push(`head: ${result.head ? `#${result.head.sequence_number} ${result.head.entry_hash}` : 'none'}`);
  const s = result.signatures;
  lines.push(`signatures: ${s.verified} verified · ${s.unsigned} unsigned · ${s.unverifiable} unverifiable`);
  lines.push('  unsigned entries are not a defect: signing did not exist when they were written, or the writing instance had no key; they are checked by recomputation only');
  if (s.missing_key_ids.length) lines.push(`  public key not in the registry: ${s.missing_key_ids.join(', ')}`);
  for (const [k, n] of Object.entries(s.compromised_key_ids)) {
    lines.push(`  ${k} is marked compromised in the registry and signed ${n} verified ${n === 1 ? 'entry' : 'entries'}; what that means is not a computation`);
  }
  lines.push('');
  lines.push('This establishes that the entries have not changed since they were hashed and, where signed, since they were signed with the named key. It does not establish when they were signed, that the holder of that key did not fabricate them, or that this dump is the chain the operator holds.');
  return lines.join('\n');
}

const EXIT = { VALID: 0, BROKEN: 1, UNVERIFIABLE: 2 };

// ─────────────────────────────────────────────────────────────── self-test

function selfTest() {
  const here = dirname(fileURLToPath(import.meta.url));
  const load = (name) => new JsonReader(readFileSync(join(here, 'fixtures', name), 'utf8')).read();
  const failures = [];
  const check = (label, got, want) => {
    if (JSON.stringify(got) !== JSON.stringify(want)) failures.push(`${label}\n    got  ${JSON.stringify(got)}\n    want ${JSON.stringify(want)}`);
  };

  // ── the specification's own vectors (§10)
  const v = load('vectors.json');
  const g = v.get('genesis');
  check('§10.1 genesis', genesisHash(g.get('tenant_id')), g.get('hash'));
  check('§10.1 genesis preimage', sha256hex(g.get('preimage')), g.get('hash'));

  const f = v.get('entry').get('fields');
  const fields = {
    id: f.get('id'), tenant_id: f.get('tenant_id'), sequence_number: toNumber(f.get('sequence_number'), 'seq'),
    outbox_id: f.get('outbox_id'), event_type: f.get('event_type'), drained_at: f.get('drained_at'),
    payload_hash: f.get('payload_hash'), prev_hash: f.get('prev_hash'),
  };
  for (const scheme of ['v1', 'v2']) {
    const want = v.get('entry').get(scheme);
    const got = entryHash(scheme, fields);
    check(`§10.2 ${scheme} preimage`, got.preimage, want.get('preimage'));
    check(`§10.2 ${scheme} entry_hash`, got.hash, want.get('entry_hash'));
  }

  for (const row of v.get('timestamps_v1')) {
    check(`§3.1 v1 ${row.get('stored')}`, drainedAtV1(parseInstant(row.get('stored'))), row.get('v1'));
  }

  for (const [name, p] of v.get('payload')) {
    check(`§10.3 ${name} v1`, payloadHash('v1', p.get('document')), p.get('v1'));
    check(`§10.3 ${name} v2`, payloadHash('v2', p.get('document')), p.get('v2'));
  }

  for (const c of v.get('canonical')) {
    const doc = new JsonReader(c.get('text')).read();
    check(`§10.4 ${c.get('text')} (canonical text)`, canonical(doc, true), c.get('text') === '{"n":1.0}' ? '{"n":1}' : c.get('text'));
    check(`§10.4 ${c.get('text')} (hash)`, sha256hex(canonical(doc, true)), c.get('hash'));
  }

  const s = v.get('signature');
  const der = Buffer.from(s.get('public_key'), 'base64');
  check('§10.5 key_id derivation', keyIdOf(der), s.get('key_id'));
  check('§10.5 signed bytes', signedBytes(s.get('key_id'), s.get('signature_scheme'), s.get('entry_hash')).toString('utf8'), s.get('signed_bytes'));
  const pub = createPublicKey({ key: der, format: 'der', type: 'spki' });
  check('§10.5 signature verifies', cryptoVerify(null, signedBytes(s.get('key_id'), s.get('signature_scheme'), s.get('entry_hash')), pub, Buffer.from(s.get('signature'), 'base64')), true);

  // ── a sample chain produced by the shipped implementation, and mutations of it
  const registry = loadRegistry(load('sample-registry.json'));
  const dump = () => readDump(load('sample-chain.json'));
  const keyId = [...registry.keys()][0];
  const summary = (r) => ({ status: r.status, break: r.first_break && [r.first_break.sequence_number, r.first_break.kind], verified: r.verified_entries, sig: [r.signatures.verified, r.signatures.unsigned, r.signatures.unverifiable], from: r.unverifiable_from_sequence });

  const run = (label, mutate, registryOverride, want) => {
    const d = dump();
    mutate(d);
    check(`chain: ${label}`, summary(verifyChain(d, registryOverride ?? registry)), want);
  };
  const S = (status, brk, verified, sig, from = null) => ({ status, break: brk, verified, sig, from });

  run('untouched', () => {}, null, S('VALID', null, 3, [1, 2, 0]));
  run('payload of #2 altered → check 5', (d) => { d.entries[1].payload = new Map([['x', true]]); }, null, S('BROKEN', [2, 'ENTRY_ALTERED'], 1, [0, 1, 0]));
  run('entry_hash of #2 altered → check 7', (d) => { d.entries[1].entry_hash = 'f'.repeat(64); }, null, S('BROKEN', [2, 'ENTRY_ALTERED'], 1, [0, 1, 0]));
  run('prev_hash of #3 altered → check 6', (d) => { d.entries[2].prev_hash = 'f'.repeat(64); }, null, S('BROKEN', [3, 'LINK_BROKEN'], 2, [0, 2, 0]));
  run('prev_hash of #1 altered → genesis link', (d) => { d.entries[0].prev_hash = 'f'.repeat(64); }, null, S('BROKEN', [1, 'LINK_BROKEN'], 0, [0, 0, 0]));
  run('#2 removed → check 1', (d) => { d.entries.splice(1, 1); }, null, S('BROKEN', [3, 'ENTRY_MISSING'], 1, [0, 1, 0]));
  run('signature of #3 flipped → check 8', (d) => { const b = Buffer.from(d.entries[2].signature, 'base64'); b[0] ^= 1; d.entries[2].signature = b.toString('base64'); }, null, S('BROKEN', [3, 'SIGNATURE_INVALID'], 2, [0, 2, 0]));
  run('key_id of #3 nulled → partial triple', (d) => { d.entries[2].key_id = null; }, null, S('BROKEN', [3, 'SIGNATURE_INVALID'], 2, [0, 2, 0]));
  run('payload_hash of #2 nulled → check 4', (d) => { d.entries[1].payload_hash = null; }, null, S('BROKEN', [2, 'ENTRY_MALFORMED'], 1, [0, 1, 0]));
  run('precedence: #2 payload AND link altered → check 5 wins', (d) => { d.entries[1].payload = new Map([['x', true]]); d.entries[1].prev_hash = 'f'.repeat(64); }, null, S('BROKEN', [2, 'ENTRY_ALTERED'], 1, [0, 1, 0]));
  run('empty registry → signature unverifiable', () => {}, new Map(), S('UNVERIFIABLE', null, 3, [0, 2, 1]));
  run('#2 under an unknown hash scheme → stop', (d) => { d.entries[1].hash_scheme = 'v9'; }, null, S('UNVERIFIABLE', null, 1, [0, 1, 0], 2));
  run('#3 payload redacted → still verifies through payload_hash', (d) => { d.entries[2].payload = null; }, null, S('VALID', null, 3, [1, 2, 0]));
  run('#3 payload re-encoded NFD→NFC → same hash under v2', (d) => { d.entries[2].payload.set('actor', 'josé'); }, null, S('VALID', null, 3, [1, 2, 0]));
  run('#1 and #2 turned into pre-chain rows → honest prefix', (d) => { for (const i of [0, 1]) { d.entries[i].entry_hash = null; d.entries[i].prev_hash = null; d.entries[i].payload_hash = null; d.entries[i].hash_scheme = null; } d.entries[2].prev_hash = genesisHash(d.tenantId); }, null, S('BROKEN', [3, 'ENTRY_ALTERED'], 0, [0, 0, 0]));
  const emptyDump = { tenantId: '00000000-0000-7000-8000-000000000001', entries: [] };
  check('chain: empty ledger is trivially VALID', summary(verifyChain(emptyDump, registry)), S('VALID', null, 0, [0, 0, 0]));
  check('registry: missing-key test names the key', verifyChain(dump(), new Map()).signatures.missing_key_ids, [keyId]);

  if (failures.length) {
    console.error(`self-test: ${failures.length} failure(s)\n\n  ` + failures.join('\n\n  '));
    return 1;
  }
  console.log('self-test: every vector of docs/spec/audit-chain-format.md section 10 reproduced, and the sample chain behaves as specified under 16 mutations');
  return 0;
}

// ─────────────────────────────────────────────────────────────────── main

function main(argv) {
  if (argv.includes('--self-test')) return selfTest();
  const json = argv.includes('--json');
  const files = argv.filter((a) => !a.startsWith('--'));
  if (files.length !== 2) {
    console.error('usage: node verify.mjs <dump.json> <registry.json> [--json]\n       node verify.mjs --self-test');
    return 3;
  }
  let result;
  try {
    const dump = readDump(new JsonReader(readFileSync(files[0], 'utf8')).read());
    const registry = loadRegistry(new JsonReader(readFileSync(files[1], 'utf8')).read());
    result = verifyChain(dump, registry);
  } catch (err) {
    console.error(`could not verify: ${err.message}`);
    return 3;
  }
  console.log(json ? JSON.stringify(result, null, 2) : render(result));
  return EXIT[result.status];
}

process.exit(main(process.argv.slice(2)));
