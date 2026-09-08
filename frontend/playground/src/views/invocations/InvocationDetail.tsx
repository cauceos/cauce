import { formatUtc, shortId } from '../../lib/format'
import type { LedgerEntry } from '../../session/LedgerContext'
import { ledgerStatus } from '../../session/LedgerContext'
import { StatusChip, approxSpan, clockOf, serverSpan, watchEndText } from './LedgerRow'

/**
 * One invocation, as this browser saw it: the phases the client actually
 * passed through, stamped with its own clock. Queue time, model latency
 * and tool time are not separable from here — the API exposes no per-phase
 * server timings, so none are shown.
 */
export function InvocationDetail({
  entry,
  now,
  refreshing,
  onOpenConversation,
  onRefresh,
}: {
  entry: LedgerEntry
  now: number
  refreshing: boolean
  onOpenConversation(): void
  onRefresh(): void
}) {
  const status = ledgerStatus(entry)
  const observed = entry.origin === 'observed' && entry.sentAt !== null

  return (
    <>
      <div className="p-h">{entry.invocationId}</div>
      <div className="p-st">
        <StatusChip entry={entry} />
      </div>

      <div className="kv">
        <span className="k">Agent</span>
        <span className="v">
          {entry.agentName !== null
            ? `${entry.agentName} · ${entry.agentId !== null ? shortId(entry.agentId) : ''}`
            : entry.agentId !== null
              ? entry.agentId
              : '— not observed'}
        </span>
      </div>
      <div className="kv">
        <span className="k">Conversation</span>
        <span className="v">{entry.conversationId ?? '—'}</span>
      </div>
      <div className="kv">
        <span className="k">Trigger message</span>
        <span className="v">{entry.triggerMessageId ?? '—'}</span>
      </div>
      {entry.idempotencyKey !== null && (
        <div className="kv">
          <span className="k">Idempotency-Key</span>
          <span className="v">{entry.idempotencyKey}</span>
        </div>
      )}
      {entry.failureReason !== null && (
        <div className="kv">
          <span className="k">Failure reason</span>
          <span className="v">{entry.failureReason}</span>
        </div>
      )}

      <div className="p-sep" />

      {observed && entry.sentAt !== null ? (
        <div className="phases">
          <div className="ph ok">
            <span className="d" />
            <span className="txt">
              <span className="t1">Accepted — 202 from the API</span>
              <span className="t2">{clockOf(entry.sentAt, true)} · invocation_id returned</span>
            </span>
          </div>
          <div className={entry.boundAt !== null ? 'ph ok' : 'ph'}>
            <span className="d" />
            <span className="txt">
              <span className="t1">Thread walked, invocation bound</span>
              <span className="t2">{entry.boundAt !== null ? clockOf(entry.boundAt, true) : 'not yet'}</span>
            </span>
          </div>
          <div className={status === 'PENDING' ? 'ph on' : status === 'UNKNOWN' ? 'ph' : 'ph ok'}>
            <span className="d" />
            <span className="txt">
              <span className="t1">Polling every 2s</span>
              <span className="t2">
                {entry.polls} {entry.polls === 1 ? 'tick' : 'ticks'} ·{' '}
                {status === 'PENDING'
                  ? `≈ ${approxSpan(now - entry.sentAt)} elapsed · cap 5:00`
                  : status === 'UNKNOWN'
                    ? `stopped: ${watchEndText(entry.watchEnd)}`
                    : `≈ ${approxSpan((entry.terminalAt ?? now) - entry.sentAt)} to terminal`}
              </span>
            </span>
          </div>
          <div className={entry.terminalAt !== null || entry.resolvedByLookup ? 'ph ok' : 'ph'}>
            <span className="d" />
            <span className="txt">
              <span className="t1">Terminal status</span>
              <span className="t2">
                {entry.terminalAt !== null
                  ? `${clockOf(entry.terminalAt, true)} · ${status}`
                  : entry.resolvedByLookup
                    ? `${status} · resolved by lookup, never observed live`
                    : status === 'UNKNOWN'
                      ? 'never observed — the client stopped watching'
                      : 'not yet observed'}
              </span>
            </span>
          </div>
        </div>
      ) : (
        <p className="p-note">
          <b>Not observed by this session.</b> Server facts only —{' '}
          {entry.serverCreatedAt !== null && <>created {formatUtc(entry.serverCreatedAt)}</>}
          {entry.serverCompletedAt !== null && <>, completed {formatUtc(entry.serverCompletedAt)}</>}
          {serverSpan(entry) !== null && <> ({approxSpan(serverSpan(entry) ?? 0)} server-side)</>}.
        </p>
      )}

      <p className="p-note">
        <b>Client-observed only.</b> Queue time, model latency and tool time are not separable
        from here — the API exposes no per-phase server timings.
      </p>

      <div className="p-acts">
        <button
          className="p-link"
          type="button"
          disabled={entry.conversationId === null}
          onClick={onOpenConversation}
        >
          Open in conversation
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" aria-hidden="true">
            <path d="M5 12h13M13 6l6 6-6 6" />
          </svg>
        </button>
        {/* GET /v1/invocations/{id}: the server's current word on this row.
            It can resolve an UNKNOWN — and says so when it does. */}
        <button className="btn-ghost sm" type="button" disabled={refreshing} onClick={onRefresh}>
          {refreshing ? 'Refreshing…' : 'Refresh from server'}
        </button>
      </div>
    </>
  )
}
