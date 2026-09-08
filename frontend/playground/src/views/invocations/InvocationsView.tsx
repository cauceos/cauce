import { Fragment, useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import type { ApiClient } from '../../api/client'
import { ApiError, NetworkError } from '../../api/errors'
import { BottomSheet } from '../../components/BottomSheet'
import { MOBILE_QUERY, TABLET_QUERY, useMediaQuery } from '../../lib/useMediaQuery'
import { ledgerStatus, useLedger } from '../../session/LedgerContext'
import { useSession } from '../../session/SessionContext'
import { InvocationDetail } from './InvocationDetail'
import { LedgerRow } from './LedgerRow'

export function InvocationsView() {
  const { client } = useSession()
  // RequireSession guarantees a connected session; this guard is for the
  // type system (and the disconnect unmount race).
  if (client === null) return null
  return <ConnectedInvocations client={client} />
}

/**
 * The session ledger, not a server query: the API has no invocation
 * listing, so this screen shows what this browser started itself and what
 * it observed happening. A reload empties it. The one server read is the
 * lookup by id (GET /v1/invocations/{id}), which yields server facts — and
 * can resolve a row this browser stopped watching.
 */
function ConnectedInvocations({ client }: { client: ApiClient }) {
  const { entries, lookup, clear } = useLedger()
  const navigate = useNavigate()
  const isMobile = useMediaQuery(MOBILE_QUERY)
  const isTablet = useMediaQuery(TABLET_QUERY)

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [lookupInput, setLookupInput] = useState('')
  const [lookupBusy, setLookupBusy] = useState<string | null>(null)
  const [lookupError, setLookupError] = useState<string | null>(null)

  const inFlight = entries.filter((e) => ledgerStatus(e) === 'PENDING').length
  const now = useNow(inFlight > 0)
  const selected = selectedId !== null ? (entries.find((e) => e.invocationId === selectedId) ?? null) : null

  async function lookUp(rawId: string) {
    const id = rawId.trim()
    if (id === '' || lookupBusy !== null) return
    setLookupBusy(id)
    setLookupError(null)
    try {
      const invocation = await client.getInvocation(id)
      lookup(invocation)
      setSelectedId(invocation.id)
      setLookupInput('')
    } catch (cause) {
      setLookupError(describeLookupError(cause))
    } finally {
      setLookupBusy(null)
    }
  }

  function openConversation() {
    if (selected === null || selected.conversationId === null) return
    void navigate('/conversation', {
      state: { agentId: selected.agentId ?? undefined, conversationId: selected.conversationId },
    })
  }

  const detail =
    selected !== null ? (
      <InvocationDetail
        entry={selected}
        now={now}
        refreshing={lookupBusy === selected.invocationId}
        onOpenConversation={openConversation}
        onRefresh={() => void lookUp(selected.invocationId)}
      />
    ) : (
      <p className="panel-empty">Select a row to see what this browser observed of it.</p>
    )

  return (
    <div className="invocations">
      <div className="page-head">
        <div className="eyebrow">
          <span className="rule" />
          Playground · Runtime
        </div>
        <h1>Invocations</h1>
        <p className="page-sub">
          Everything this session sent to an agent, and what came back — <b>as this browser
          observed it</b>. Timings are wall clock at the client, not server metrics.
        </p>
      </div>

      <div className="banner">
        <p className="t">
          <b>This is a session ledger, not a server query.</b> The API has no invocation listing,
          so this screen can only show what it started itself. A reload empties it.
        </p>
        <span className="count">
          {entries.length} this session · {inFlight} in flight
        </span>
      </div>

      <div className="action-row">
        <div className="f">
          <label htmlFor="inv-lookup">Look up an invocation by id</label>
          <input
            className="input"
            id="inv-lookup"
            type="text"
            placeholder="01a03961-…"
            value={lookupInput}
            onChange={(event) => setLookupInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void lookUp(lookupInput)
            }}
            spellCheck={false}
            autoCapitalize="off"
            autoCorrect="off"
          />
        </div>
        <button
          className="btn"
          type="button"
          onClick={() => void lookUp(lookupInput)}
          disabled={lookupBusy !== null || lookupInput.trim() === ''}
        >
          {lookupBusy !== null && lookupBusy === lookupInput.trim() ? 'Looking up…' : 'Look up'}
        </button>
        <button
          className="btn-ghost"
          type="button"
          onClick={() => {
            clear()
            setSelectedId(null)
          }}
          disabled={entries.length === 0}
        >
          Clear ledger
        </button>
        <p className="hint">
          Any id this key can see — this session&apos;s or not. An id this browser did not send
          arrives with server facts only: status and server duration, no observed phases.
        </p>
        {lookupError !== null && <span className="note">{lookupError}</span>}
      </div>

      <div className="split">
        <div>
          <div className="ledger">
            <div className="l-head">
              <span>Invocation</span>
              <span>Agent · conversation</span>
              <span>Observed</span>
              <span>Status</span>
            </div>

            {entries.length === 0 && (
              <div className="ledger-empty">
                <div className="t">Nothing sent yet this session.</div>
                <div className="d">
                  Send a message from Conversation — it lands here as it happens.
                  <br />
                  Also the state after a reload.
                </div>
              </div>
            )}

            {entries.map((entry) => (
              <Fragment key={entry.invocationId}>
                <LedgerRow
                  entry={entry}
                  now={now}
                  selected={entry.invocationId === selectedId}
                  onSelect={() => setSelectedId(entry.invocationId)}
                />
                {/* Tablet: the detail expands under its row. */}
                {isTablet && entry.invocationId === selectedId && <div className="detail">{detail}</div>}
              </Fragment>
            ))}

            {entries.length > 0 && (
              <div className="ledger-note">
                Bars are client wall clock: <span className="sl">slate</span> waiting ·{' '}
                <span className="tl">teal</span> answering. &quot;Waiting&quot; includes poll latency
                (2s granularity) — hence ≈ everywhere. UNKNOWN is dashed: this browser stopped
                watching, the invocation kept living. FAILED means the server said so.
              </div>
            )}
          </div>

          <div className="gapbox">
            <div className="t">Absent on purpose</div>
            <div className="d">
              <b>No tokens, no cost.</b> Per-call usage is persisted server-side but not exposed by
              any endpoint, so there is no number here this screen could stand behind.
            </div>
            <div className="d">
              <b>No per-phase server timings.</b> Queue, model and tool time are not separable
              from a client.
            </div>
            <div className="d">
              <b>Reaper-abandoned invocations</b> leave no conversation-visible trace and no
              terminal status this browser can see. They would sit here as UNKNOWN forever — a
              lookup shows what the server has, which may be nothing new.
            </div>
          </div>
        </div>

        <aside className="panel">{detail}</aside>
      </div>

      {isMobile && (
        <BottomSheet open={selected !== null} onClose={() => setSelectedId(null)} label="Invocation detail">
          {detail}
        </BottomSheet>
      )}
    </div>
  )
}

/** A 1s clock, only while something is in flight — the ≈ elapsed readouts. */
function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    setNow(Date.now())
    if (!active) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [active])
  return now
}

function describeLookupError(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 404) return 'no invocation visible for this id (it may not exist, or not be yours)'
    if (cause.status === 400) return 'not a valid invocation id'
    return `${cause.status} ${cause.code} — ${cause.message}`
  }
  if (cause instanceof NetworkError) return 'request failed — is the instance still reachable?'
  return cause instanceof Error ? cause.message : String(cause)
}
