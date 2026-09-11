import { useState } from 'react'
import type { ApiClient } from '../../api/client'
import { ApiError, NetworkError } from '../../api/errors'
import type { ChainVerificationResponse, TenantResponse } from '../../api/types'
import { useSession } from '../../session/SessionContext'
import { ScopePanel } from './ScopePanel'
import { VerdictPanel } from './VerdictPanel'

export function AuditChainView() {
  const { client } = useSession()
  // RequireSession guarantees a connected session; this guard is for the
  // type system (and the disconnect unmount race).
  if (client === null) return null
  return <ConnectedAuditChain client={client} />
}

interface Verification {
  result: ChainVerificationResponse
  tenant: TenantResponse | null
}

/**
 * Every runtime and administration event of a tenant is hash-chained into
 * an append-only record. This screen recomputes the whole chain and reports
 * a computational fact — together with what that fact cannot mean.
 *
 * The verdict is never cached, not even across navigations: the response
 * carries `verified_at`, and showing a stored one would present a stale
 * fact as a current one. Running it again is the only way to know again.
 */
function ConnectedAuditChain({ client }: { client: ApiClient }) {
  const { tenantId, setTenantId } = useSession()

  const [tenantInput, setTenantInput] = useState(tenantId)
  const [running, setRunning] = useState(false)
  const [verification, setVerification] = useState<Verification | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Verifying is an explicit action, and stays one. There is no auto-refresh,
  // no retry and no polling here, and there must never be: every call to the
  // endpoint appends a `ledger.chain.verified` entry to the chain it verified
  // (ADR 0003 §5). A timer on this screen would make the chain grow by itself,
  // one entry per tick, recording verifications nobody asked for.
  async function run() {
    const id = tenantInput.trim()
    if (id === '' || running) return
    setRunning(true)
    setNotFound(false)
    setError(null)
    try {
      const result = await client.verifyChain(id)
      // Best-effort name for the eyebrow; the verdict stands without it.
      let tenant: TenantResponse | null = null
      try {
        tenant = await client.getTenant(id)
      } catch {
        tenant = null
      }
      setVerification({ result, tenant })
      setTenantId(id)
    } catch (cause) {
      setVerification(null)
      // Hierarchical visibility: a tenant you cannot see and one that does
      // not exist are the same 404. The copy owns the ambiguity.
      if (cause instanceof ApiError && cause.status === 404) setNotFound(true)
      else setError(describeError(cause))
    } finally {
      setRunning(false)
    }
  }

  return (
    <div className="audit">
      <div className="page-head">
        <div className="eyebrow">
          <span className="rule" />
          Playground · Trust
        </div>
        <h1>Audit chain</h1>
        <p className="page-sub">
          Every runtime and administration event of a tenant is hash-chained into an append-only
          record. This screen <b>recomputes the whole chain</b> and reports a computational fact —
          together with what that fact cannot mean.
        </p>
      </div>

      <div className="action-row">
        <div className="f">
          <label htmlFor="audit-tenant">
            Tenant id <span className="tag">· from your key</span>
          </label>
          <input
            className="input"
            id="audit-tenant"
            type="text"
            placeholder="your tenant, or a visible subordinate"
            value={tenantInput}
            onChange={(event) => setTenantInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void run()
            }}
            spellCheck={false}
            autoCapitalize="off"
            autoCorrect="off"
          />
        </div>
        <button
          className="btn"
          type="button"
          onClick={() => void run()}
          disabled={running || tenantInput.trim() === ''}
        >
          {running ? 'Recomputing…' : 'Run verification'}
        </button>
        {/* The wait is real, so the button says why rather than faking a
            percentage: the API answers once, at the end. */}
        <p className="note">
          Full recomputation on demand — O(n) in time and memory; a long chain takes longer.
          Nothing is cached here. A wrong id and one outside your key&apos;s scope both answer 404.
        </p>

        {notFound && (
          <div className="error-note quiet">
            <div className="t">No tenant is visible at that id.</div>
            <div className="d">
              A wrong id and one outside your key&apos;s scope look the same on purpose.
            </div>
          </div>
        )}
        {error !== null && (
          <div className="error-note" role="alert">
            <div className="d">{error}</div>
          </div>
        )}
      </div>

      <div className="chain-split">
        {running ? (
          <RunningVerdict />
        ) : verification !== null ? (
          <VerdictPanel result={verification.result} tenant={verification.tenant} />
        ) : (
          <IdleVerdict />
        )}

        {/* The scope panel exists only once a response defines it: its text
            is the API's, never this screen's. */}
        {verification !== null && <ScopePanel scope={verification.result.verification_scope} />}
      </div>
    </div>
  )
}

/** Recomputing: figures as skeletons, no progress bar — see the note above. */
function RunningVerdict() {
  return (
    <section className="card verdict" aria-busy="true">
      <div className="v-eyebrow">Recomputing the chain</div>
      <div className="figs">
        <div className="fig">
          <span className="skel" />
          <span className="l">Entries verified</span>
        </div>
        <div className="fig pre">
          <span className="skel" />
          <span className="l">Pre-chain entries</span>
        </div>
      </div>
      <p className="chain-gap">
        The whole chain is being rebuilt and re-checked, entry by entry. The API answers once,
        when it is done.
      </p>
    </section>
  )
}

function IdleVerdict() {
  return (
    <section className="card verdict">
      <div className="v-eyebrow">No verification run yet</div>
      <div className="chain-empty">
        <div className="t">Nothing checked yet.</div>
        <div className="d">
          Run the verification above. It recomputes the chain from genesis and reports what it
          finds — and what that finding cannot cover.
        </div>
      </div>
    </section>
  )
}

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 400) return 'not a valid tenant id'
    return `${cause.status} ${cause.code} — ${cause.message}`
  }
  if (cause instanceof NetworkError) return 'request failed — is the instance still reachable?'
  return cause instanceof Error ? cause.message : String(cause)
}
