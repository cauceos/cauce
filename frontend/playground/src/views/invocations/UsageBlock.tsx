import type { InvocationUsageResponse } from '../../api/types'
import type { LedgerStatus } from '../../session/LedgerContext'

/**
 * Token usage for one invocation, as the ledger recorded it. Tokens only —
 * no cost, no price, no currency: prices change per provider and model,
 * and a monetary number computed in a screen is the kind someone later
 * bills from.
 *
 * Two distinctions the backend models and this block must not flatten:
 *
 * - `null` is not zero. When no provider call was recorded there is no
 *   number here at all, and the copy says "not recorded". An invocation
 *   that failed before a provider answered did not spend zero tokens; we
 *   simply have no record of what it spent.
 * - `complete: false` means the totals are a FLOOR. Usage is written only
 *   after a provider responds, so a round that failed on the way out
 *   contributes nothing. Each figure carries a ≥ and the block says so.
 *
 * `undefined` is a third, client-side state: the wire has not delivered
 * usage yet (the invocation is still in flight), or this instance does not
 * expose the field. It is named as what it is, not shown as absent data.
 */
export function UsageBlock({
  usage,
  status,
}: {
  usage: InvocationUsageResponse | null | undefined
  status: LedgerStatus
}) {
  return (
    <div className="kv usage">
      <span className="k">
        Tokens
        {usage !== null && usage !== undefined && !usage.complete && (
          <span className="chip quiet u-chip">floor</span>
        )}
      </span>

      {usage === undefined ? (
        <span className="u-absent">
          {status === 'PENDING'
            ? 'not yet — recorded as each provider call answers; lands with the terminal status'
            : status === 'UNKNOWN'
              ? 'not seen — this browser stopped watching; refresh from server'
              : 'not provided by this instance'}
        </span>
      ) : usage === null ? (
        <>
          <span className="u-absent">not recorded</span>
          {/* Deliberately no digit here: a 0 would state a fact the ledger never captured. */}
          <span className="u-note">
            No provider call was logged for this invocation — it failed before a provider
            answered, or predates the usage ledger. Not zero: unrecorded.
          </span>
        </>
      ) : (
        <>
          <span className="u-nums">
            <Figure label="input" value={usage.input_tokens} floor={!usage.complete} />
            <Figure label="output" value={usage.output_tokens} floor={!usage.complete} />
            <Figure label="total" value={usage.total_tokens} floor={!usage.complete} />
          </span>
          {!usage.complete && (
            <span className="u-note">
              At least. The invocation did not complete, and usage is recorded only after a
              provider answers, so the round that failed on the way out is not counted. These
              are a floor, not the amount.
            </span>
          )}
          {usage.calls.length > 1 && <Calls usage={usage} />}
        </>
      )}
    </div>
  )
}

function Figure({ label, value, floor }: { label: string; value: number; floor: boolean }) {
  return (
    <span>
      {label}{' '}
      <span className="n">
        {floor && <span className="ge">≥ </span>}
        {value.toLocaleString('en-US')}
      </span>
    </span>
  )
}

/**
 * The per-call breakdown, shown only when there is more than one call: with
 * one, it would merely repeat the totals. It exists because a worker retry
 * re-runs the agentic loop from round zero, so the same round_index can
 * appear more than once inside one invocation — each occurrence a real,
 * provider-billed call. The aggregate alone cannot show that the same work
 * was paid for twice; this list can, and marks it.
 */
function Calls({ usage }: { usage: InvocationUsageResponse }) {
  const seen = new Set<number>()
  return (
    <span className="u-calls">
      {usage.calls.map((call, index) => {
        const again = seen.has(call.round_index)
        seen.add(call.round_index)
        return (
          <span className={again ? 'u-call again' : 'u-call'} key={`${index}-${call.recorded_at}`}>
            <span className="r">
              round {call.round_index}
              {again && ' again'}
            </span>
            <span className="m">
              {call.provider} · {call.model} · {call.finish_reason}
            </span>
            <span className="v">
              {call.input_tokens.toLocaleString('en-US')} / {call.output_tokens.toLocaleString('en-US')} /{' '}
              {call.total_tokens.toLocaleString('en-US')}
            </span>
          </span>
        )
      })}
      <span className="u-legend">
        in / out / total per provider call, oldest first
        {seen.size < usage.calls.length && ' · "again": a retry re-ran that round, and it was paid for twice'}
      </span>
    </span>
  )
}
