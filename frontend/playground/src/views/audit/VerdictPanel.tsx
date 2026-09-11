import type { ChainVerificationResponse, SignatureSummary, TenantResponse } from '../../api/types'
import { formatUtc, shortId } from '../../lib/format'

/**
 * The verdict: a computational fact, stated in typography rather than in a
 * traffic light. Three states, and the third is the one that matters most
 * to get right:
 *
 * - VALID: recomputation and every checkable signature are consistent —
 *   no more than that, which is why the scope panel beside it carries
 *   equal weight.
 * - BROKEN: an inconsistency was found, at an exact sequence.
 * - UNVERIFIABLE: no answer. Part of the chain could not be looked at —
 *   a public key this instance does not have, or a hash scheme this
 *   build does not implement. It is neither moss nor slate: it inherits
 *   the treatment pre-chain entries already had ("neither good news nor
 *   bad: subtle"), because that is exactly what it is, raised to a
 *   verdict. A reader must not come away thinking either "fine" or
 *   "broken".
 */
export function VerdictPanel({
  result,
  tenant,
}: {
  result: ChainVerificationResponse
  /** Best-effort name for the eyebrow; the id alone is honest without it. */
  tenant: TenantResponse | null
}) {
  const broken = result.status === 'BROKEN'
  const unverifiable = result.status === 'UNVERIFIABLE'
  // Zero entries verifies as VALID trivially. Saying "0 verified · VALID"
  // without framing would read as a boast about nothing. Only VALID
  // qualifies: a chain whose FIRST entry is unreadable is UNVERIFIABLE
  // with 0 verified, and that is not "nothing recorded".
  const empty =
    result.status === 'VALID' && result.verified_entries === 0 && result.pre_chain_entries === 0

  const tone = broken ? 'broken' : unverifiable ? 'unverifiable' : ''

  return (
    <section className={`card verdict ${tone}`.trim()} aria-live="polite">
      <div className="v-eyebrow">
        Verification result · {tenant !== null ? tenant.name : shortId(result.tenant_id)}
      </div>

      {empty ? (
        <div className="chain-empty">
          <div className="t">Nothing recorded yet.</div>
          <div className="d">
            This tenant has no chain entries. Send a message or create something — events land
            here.
          </div>
        </div>
      ) : (
        <>
          <div className="v-line">
            <VerdictWord status={result.status} />
            {/* The status value itself, verbatim — never mapped to a friendlier word. */}
            <span className={`chip ${chipTone(result.status)} brk-chip`}>{result.status}</span>
          </div>

          {broken && result.first_break !== null ? (
            <BreakDetail
              sequenceNumber={result.first_break.sequence_number}
              classification={result.first_break.classification}
              verifiedEntries={result.verified_entries}
            />
          ) : unverifiable ? (
            <UnverifiableDetail result={result} />
          ) : (
            <p className="v-sub">
              Recomputation is consistent from genesis to head
              {result.signatures.verified > 0
                ? ', and every signature that could be checked verifies'
                : ''}
              . No break is detectable this way.
            </p>
          )}
        </>
      )}

      <div className="figs">
        <div className="fig">
          <span className="n">{result.verified_entries}</span>
          <span className="l">Entries verified</span>
        </div>
        <div className="fig pre">
          <span className="n">{result.pre_chain_entries}</span>
          <span className="l">Pre-chain entries</span>
          {/* Kept visible always: a bare number here would read as a defect. */}
          <span className="why">Written before chaining began — not failures, and not verifiable.</span>
        </div>
      </div>

      <SignatureFigures signatures={result.signatures} />

      <div className="chain-meta">
        <span>
          verified_at <span className="v">{formatUtc(result.verified_at)}</span>
        </span>
        <span>
          tenant <span className="v">{shortId(result.tenant_id)}</span>
        </span>
        {/* The head is data, not an action: the publishable commitment to the
            whole chain up to this point. Nothing to do with it here yet. */}
        <span>
          head{' '}
          {result.head !== null ? (
            <span className="v">
              #{result.head.sequence_number} · {result.head.entry_hash}
            </span>
          ) : (
            <span className="v">none — no chained entries</span>
          )}
        </span>
      </div>

      <p className="chain-gap">
        Browsing individual entries isn&apos;t exposed by the API — this is a verification
        verdict, not an event browser. The screen says so rather than simulating a list.
      </p>
    </section>
  )
}

/** The big word. A status this build does not know renders neutrally, as-is. */
function VerdictWord({ status }: { status: string }) {
  switch (status) {
    case 'VALID':
      return (
        <span className="v-word">
          Chain <em>intact</em>.
        </span>
      )
    case 'BROKEN':
      return <span className="v-word broken">Chain broken.</span>
    case 'UNVERIFIABLE':
      return <span className="v-word unverifiable">No verdict.</span>
    default:
      return <span className="v-word unverifiable">{status}</span>
  }
}

function chipTone(status: string): string {
  switch (status) {
    case 'VALID':
      return 'moss'
    case 'BROKEN':
      return 'bad'
    default:
      return 'quiet'
  }
}

/**
 * Why there is no verdict, derived from the data and nothing else. The two
 * causes can coincide; each gets its own sentence, and neither is dressed
 * up as a failure or waved away as fine.
 */
function UnverifiableDetail({ result }: { result: ChainVerificationResponse }) {
  const stop = result.unverifiable_from_sequence
  const missing = result.signatures.missing_key_ids.length
  const uncheckable = result.signatures.unverifiable
  return (
    <>
      {stop !== null && (
        <p className="brk-d">
          The walk stopped at sequence <span className="seq">{stop}</span>: that entry uses a hash
          scheme this build does not implement. Entries before it recomputed; nothing from there
          on was checked.
        </p>
      )}
      {missing > 0 && (
        <p className="brk-d">
          <span className="seq">{uncheckable}</span> signed{' '}
          {uncheckable === 1 ? 'entry names a key id' : 'entries name key ids'} whose public key
          is not published to this instance. Their signatures were not checked — not found
          wrong, not checked.
        </p>
      )}
      {stop === null && missing === 0 && (
        <p className="brk-d">
          Part of the chain could not be checked by this instance. The signature figures below
          say how much.
        </p>
      )}
      {stop !== null && <StopSchematic sequenceNumber={stop} verifiedEntries={result.verified_entries} />}
    </>
  )
}

/**
 * Signature coverage: a second row of figures inside the verdict, because
 * coverage is part of the fact, not part of the scope. `unsigned` gets the
 * same neutral treatment as pre-chain entries and for the same reason — a
 * bare number would read as a defect, and it is not one. The API says why
 * in its own words (`note`), rendered verbatim and always visible.
 */
function SignatureFigures({ signatures }: { signatures: SignatureSummary }) {
  const compromised = Object.entries(signatures.compromised_key_ids)
  return (
    <div className="sigs">
      <span className="k">Signatures</span>
      <div className="figs">
        <div className="fig">
          <span className="n">{signatures.verified}</span>
          <span className="l">Verified</span>
        </div>
        <div className="fig unsigned">
          <span className="n">{signatures.unsigned}</span>
          <span className="l">Unsigned</span>
        </div>
        <div className="fig">
          <span className="n">{signatures.unverifiable}</span>
          <span className="l">Unverifiable</span>
        </div>
      </div>
      <p className="sig-note">{signatures.note}</p>

      {signatures.missing_key_ids.length > 0 && (
        <div className="sig-keys">
          <span className="k">Public key not published here</span>
          <ul>
            {signatures.missing_key_ids.map((keyId) => (
              <li key={keyId}>{keyId}</li>
            ))}
          </ul>
        </div>
      )}
      {compromised.length > 0 && (
        <div className="sig-keys">
          <span className="k">Marked compromised in the registry</span>
          <ul>
            {compromised.map(([keyId, count]) => (
              <li key={keyId}>
                {keyId} · {count} {count === 1 ? 'entry' : 'entries'}
              </li>
            ))}
          </ul>
          {/* Reported, not judged: the API verifies those entries and says the
              key was later marked. What that means is the reader's call. */}
          <span className="sig-why">
            Their signatures verify. What a later compromise means for them is not a computation.
          </span>
        </div>
      )}
    </div>
  )
}

/**
 * Where the chain first breaks and what kind of break it is. The
 * classification is rendered exactly as it arrives: a value this build
 * does not recognise shows as-is rather than being mapped to a guess.
 *
 * One classification gets its own sentence: SIGNATURE_INVALID means the
 * entry recomputed fine and its signature did not verify, so "can no
 * longer be trusted by recomputation" would be the wrong claim.
 */
function BreakDetail({
  sequenceNumber,
  classification,
  verifiedEntries,
}: {
  sequenceNumber: number
  classification: string
  verifiedEntries: number
}) {
  return (
    <>
      <p className="brk-d">
        <span className="seq">{classification}</span> — first break at sequence{' '}
        <span className="seq">{sequenceNumber}</span>.{' '}
        {classification === 'SIGNATURE_INVALID'
          ? 'This entry recomputes, but its signature does not verify against the key it names: it is not what was signed.'
          : 'Entries from there onward can no longer be vouched for by recomputation.'}
      </p>
      <ChainSchematic sequenceNumber={sequenceNumber} verifiedEntries={verifiedEntries} />
    </>
  )
}

/** How many dots the schematic draws on each side of the break or stop. */
const SCHEMATIC_SIDE = 4

/**
 * A schematic of before / at / after — deliberately NOT a map of entries.
 * The response carries one sequence number, not a per-entry state, so the
 * dot count is fixed and the caption says what the shape means.
 */
function ChainSchematic({
  sequenceNumber,
  verifiedEntries,
}: {
  sequenceNumber: number
  verifiedEntries: number
}) {
  const before = Math.min(SCHEMATIC_SIDE, Math.max(0, sequenceNumber - 1))
  const after = Math.min(SCHEMATIC_SIDE, Math.max(0, verifiedEntries - sequenceNumber))
  return (
    <>
      <div className="links" aria-hidden="true">
        {Array.from({ length: before }, (_, i) => (
          <span className="ld ok" key={`b${i}`} />
        ))}
        <span className="ld brk" />
        {Array.from({ length: after }, (_, i) => (
          <span className="ld" key={`a${i}`} />
        ))}
      </div>
      <span className="lg">
        Schematic, not a per-entry map: recomputed up to the break, the break itself, then
        entries recomputation can no longer vouch for.
      </span>
    </>
  )
}

/**
 * The same shape for a stop, with a different glyph and a different verb:
 * a dotted ring where the walk stopped, stone after it. Nothing after the
 * stop is "broken" — it was not looked at.
 */
function StopSchematic({
  sequenceNumber,
  verifiedEntries,
}: {
  sequenceNumber: number
  verifiedEntries: number
}) {
  const before = Math.min(SCHEMATIC_SIDE, Math.max(0, verifiedEntries))
  const unchecked = sequenceNumber > verifiedEntries ? SCHEMATIC_SIDE : 0
  return (
    <>
      <div className="links" aria-hidden="true">
        {Array.from({ length: before }, (_, i) => (
          <span className="ld ok" key={`b${i}`} />
        ))}
        <span className="ld stop" />
        {Array.from({ length: unchecked }, (_, i) => (
          <span className="ld" key={`u${i}`} />
        ))}
      </div>
      <span className="lg">
        Schematic, not a per-entry map: checked up to here, the entry that could not be read,
        then entries nobody looked at.
      </span>
    </>
  )
}
