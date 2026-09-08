import type { ChainVerificationResponse, TenantResponse } from '../../api/types'
import { formatUtc, shortId } from '../../lib/format'

/**
 * The verdict: a computational fact, stated in typography rather than in a
 * traffic light. VALID means recomputation found no break — no more than
 * that, which is why the scope panel beside it carries equal weight.
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
  // Zero entries verifies as VALID trivially. Saying "0 verified · VALID"
  // without framing would read as a boast about nothing.
  const empty = !broken && result.verified_entries === 0 && result.pre_chain_entries === 0

  return (
    <section className={broken ? 'card verdict broken' : 'card verdict'} aria-live="polite">
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
            <span className={broken ? 'v-word broken' : 'v-word'}>
              {broken ? (
                'Chain broken.'
              ) : (
                <>
                  Chain <em>intact</em>.
                </>
              )}
            </span>
            <span className={broken ? 'chip bad brk-chip' : 'chip moss brk-chip'}>
              {result.status}
            </span>
          </div>

          {broken && result.first_break !== null ? (
            <BreakDetail
              sequenceNumber={result.first_break.sequence_number}
              classification={result.first_break.classification}
              verifiedEntries={result.verified_entries}
            />
          ) : (
            <p className="v-sub">
              Recomputation is consistent from genesis to head. No break is detectable by
              recomputation.
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

      <div className="chain-meta">
        <span>
          verified_at <span className="v">{formatUtc(result.verified_at)}</span>
        </span>
        <span>
          tenant <span className="v">{shortId(result.tenant_id)}</span>
        </span>
      </div>

      <p className="chain-gap">
        Browsing individual entries isn&apos;t exposed by the API — this is a verification
        verdict, not an event browser. The screen says so rather than simulating a list.
      </p>
    </section>
  )
}

/**
 * Where the chain first breaks and what kind of break it is. The
 * classification is rendered exactly as it arrives: a value this build
 * does not recognise shows as-is rather than being mapped to a guess.
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
        <span className="seq">{sequenceNumber}</span>. Entries from there onward can no longer be
        trusted by recomputation.
      </p>
      <ChainSchematic sequenceNumber={sequenceNumber} verifiedEntries={verifiedEntries} />
    </>
  )
}

/** How many dots the schematic draws on each side of the break. */
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
