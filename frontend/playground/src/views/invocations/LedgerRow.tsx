import { shortId } from '../../lib/format'
import type { LedgerEntry } from '../../session/LedgerContext'
import { ledgerStatus } from '../../session/LedgerContext'

/** `m:ss` for elapsed spans; `x.xs` under a minute. All ≈ — client clock. */
export function approxSpan(ms: number): string {
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  const minutes = Math.floor(ms / 60_000)
  const seconds = Math.floor((ms % 60_000) / 1000)
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

/** `HH:MM:SS` in UTC from a client instant. */
export function clockOf(ms: number, withMillis = false): string {
  const d = new Date(ms)
  const pad = (n: number, w = 2) => String(n).padStart(w, '0')
  const base = `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
  return withMillis ? `${base}.${pad(d.getUTCMilliseconds(), 3)}` : base
}

/** Status chip: PENDING teal · COMPLETED moss · FAILED slate · UNKNOWN dashed. */
export function StatusChip({ entry }: { entry: LedgerEntry }) {
  const status = ledgerStatus(entry)
  const cls =
    status === 'COMPLETED' ? 'chip moss' : status === 'FAILED' ? 'chip bad' : status === 'PENDING' ? 'chip teal' : 'chip quiet lost'
  return <span className={cls}>{status}</span>
}

/**
 * The waterfall and its numbers, from client wall clock only. Waiting =
 * send → first non-PENDING seen; answering = that → terminal seen. Both
 * include poll latency (2s granularity), hence ≈. A looked-up row was not
 * observed by this browser, so it gets server facts and no bar.
 */
export function Waterfall({ entry, now }: { entry: LedgerEntry; now: number }) {
  const status = ledgerStatus(entry)

  if (entry.origin === 'lookup' || entry.sentAt === null) {
    const server = serverSpan(entry)
    return (
      <span className="btime">
        {server !== null ? (
          <span>
            server <span className="v">{approxSpan(server)}</span>
          </span>
        ) : (
          <span>server duration unknown</span>
        )}
        <span>not observed here</span>
      </span>
    )
  }

  if (status === 'PENDING') {
    return (
      <>
        <span className="bar">
          <i className="live" style={{ width: '100%' }} />
        </span>
        <span className="btime">
          <span>
            waiting <span className="v">≈ {approxSpan(now - entry.sentAt)}</span>
          </span>
          <span>
            polls <span className="v">{entry.polls}</span>
          </span>
        </span>
      </>
    )
  }

  if (status === 'UNKNOWN') {
    const polled = (entry.watchEndedAt ?? now) - entry.sentAt
    return (
      <>
        <span className="bar">
          <i className="wait" style={{ width: '100%' }} />
        </span>
        <span className="btime">
          <span>
            polled <span className="v">{approxSpan(polled)}</span>
          </span>
          <span>{watchEndText(entry.watchEnd)}</span>
        </span>
      </>
    )
  }

  // Terminal, observed. The split is where the first non-PENDING status
  // was seen; if the first thing seen was already terminal, it is all wait.
  const end = entry.terminalAt ?? now
  const active = entry.firstActiveAt ?? end
  const waiting = Math.max(0, active - entry.sentAt)
  const answering = Math.max(0, end - active)
  const total = Math.max(1, waiting + answering)
  const waitPct = Math.round((waiting / total) * 100)
  return (
    <>
      <span className="bar">
        <i className="wait" style={{ width: `${waitPct}%` }} />
        {answering > 0 && <i className="ans" style={{ width: `${100 - waitPct}%` }} />}
      </span>
      <span className="btime">
        <span>
          {status === 'FAILED' ? 'failed at' : 'total'} <span className="v">≈ {approxSpan(total)}</span>
        </span>
        <span>
          polls <span className="v">{entry.polls}</span>
        </span>
        {entry.resolvedByLookup && <span>resolved by lookup</span>}
      </span>
    </>
  )
}

/** `completed_at - created_at` from the invocation row — a server span, not ≈. */
export function serverSpan(entry: LedgerEntry): number | null {
  if (entry.serverCreatedAt === null || entry.serverCompletedAt === null) return null
  const a = new Date(entry.serverCreatedAt).getTime()
  const b = new Date(entry.serverCompletedAt).getTime()
  if (Number.isNaN(a) || Number.isNaN(b) || b < a) return null
  return b - a
}

export function watchEndText(reason: LedgerEntry['watchEnd']): string {
  switch (reason) {
    case 'cap':
      return 'gave up watching at the 5:00 cap'
    case 'poll-failures':
      return 'polling failed repeatedly'
    case 'fatal':
      return 'polling stopped (4xx)'
    case 'navigated':
      return 'left the conversation'
    default:
      return 'not watched'
  }
}

export function LedgerRow({
  entry,
  now,
  selected,
  onSelect,
}: {
  entry: LedgerEntry
  now: number
  selected: boolean
  onSelect(): void
}) {
  return (
    <div
      className={selected ? 'irow sel' : 'irow'}
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          onSelect()
        }
      }}
    >
      <div className="r1">
        <span className="iid">
          {shortId(entry.invocationId)}
          <span className="sent">
            {entry.sentAt !== null ? `sent ${clockOf(entry.sentAt)}` : 'looked up'}
          </span>
        </span>
        <span className="iwhat">
          <span className="ag">
            {entry.agentName ?? (entry.agentId !== null ? shortId(entry.agentId) : '—')}
            {entry.agentName !== null && entry.agentId !== null && (
              <span className="aid"> · {shortId(entry.agentId)}</span>
            )}
          </span>
          <span className="cv">
            conv {entry.conversationId !== null ? shortId(entry.conversationId) : '—'}
          </span>
        </span>
      </div>
      <div className="r2">
        <span className="obs">
          <Waterfall entry={entry} now={now} />
        </span>
        <span className="stwrap">
          <StatusChip entry={entry} />
        </span>
      </div>
    </div>
  )
}
