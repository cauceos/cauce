import { useEffect, useRef, useState } from 'react'
import type { ApiClient } from '../../api/client'
import { ApiError } from '../../api/errors'
import type { ApiKeyCreatedResponse, ApiKeyResponse, TenantResponse } from '../../api/types'
import { BottomSheet } from '../../components/BottomSheet'
import { toFormError } from '../../lib/formError'
import type { FormError } from '../../lib/formError'
import { MOBILE_QUERY, useMediaQuery } from '../../lib/useMediaQuery'
import { useSession } from '../../session/SessionContext'
import { KeyReveal } from './KeyReveal'
import { KeyRow } from './KeyRow'
import { RevokeConfirm } from './RevokeConfirm'
import { describeError, useApiKeys } from './useApiKeys'

export function ApiKeysView() {
  const { client } = useSession()
  // RequireSession guarantees a connected session; this guard is for the
  // type system (and the disconnect unmount race).
  if (client === null) return null
  return <ConnectedApiKeys client={client} />
}

interface Reveal {
  created: ApiKeyCreatedResponse
  tenant: TenantResponse | null
}

interface Confirm {
  key: ApiKeyResponse
  stage: 'confirm' | 'session'
}

/**
 * Keys beget keys: a key can issue keys for its own tenant and any tenant
 * it can see; the issued key sees only that tenant's scope (ADR 0002).
 * Keys carry no roles — authority is tenant scope.
 *
 * The plaintext lives in this component's state, and only until "I've
 * stored it" (or a reconnect with it) — never anywhere persistent.
 */
function ConnectedApiKeys({ client }: { client: ApiClient }) {
  const { tenantId, setTenantId, identity, instanceUrl, connect, disconnect } = useSession()
  const keys = useApiKeys(client)
  const isMobile = useMediaQuery(MOBILE_QUERY)

  // Prefilled from the session tenant (the key's own, via /v1/me at
  // connect), editable to issue for a visible subordinate.
  const [tenantInput, setTenantInput] = useState(tenantId)
  const [label, setLabel] = useState('')
  const [issuing, setIssuing] = useState(false)
  const [issueError, setIssueError] = useState<FormError | null>(null)
  const [issueNotFound, setIssueNotFound] = useState(false)
  const [reveal, setReveal] = useState<Reveal | null>(null)
  const [confirm, setConfirm] = useState<Confirm | null>(null)
  const [revokingId, setRevokingId] = useState<string | null>(null)
  const [revokeError, setRevokeError] = useState<string | null>(null)

  // Auto-load on mount when a tenant is already known — same precedent as
  // the other workspace areas. Idempotent GET, StrictMode-safe (the hook
  // aborts the first).
  const loadRef = useRef(keys.load)
  loadRef.current = keys.load
  useEffect(() => {
    if (tenantId.trim() !== '') void loadRef.current(tenantId.trim())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function loadKeys() {
    const id = tenantInput.trim()
    if (id === '') return
    setTenantId(id)
    void keys.load(id)
  }

  async function issue() {
    const id = tenantInput.trim()
    if (id === '' || issuing) return
    setIssuing(true)
    setIssueError(null)
    setIssueNotFound(false)
    try {
      const trimmedLabel = label.trim()
      const created = await client.createApiKey(id, trimmedLabel === '' ? {} : { label: trimmedLabel })
      // Best-effort name for the reveal; the id alone is honest if it fails.
      let tenant: TenantResponse | null = null
      try {
        tenant = await client.getTenant(id)
      } catch {
        tenant = null
      }
      setReveal({ created, tenant })
      setLabel('')
      setTenantId(id)
      // The list is unpaginated, so a refetch cannot lose the new row; and
      // the 201 lacks the fields a listed row carries, so nothing is faked.
      void keys.load(id, keys.tenantId === id)
    } catch (cause) {
      // Hierarchical authority: a tenant you cannot see is a 404, exactly
      // like one that does not exist. The copy owns the ambiguity.
      if (cause instanceof ApiError && cause.status === 404) setIssueNotFound(true)
      else setIssueError(toFormError(cause))
    } finally {
      setIssuing(false)
    }
  }

  function connectWithKey(plaintext: string) {
    // The reconnect passes through `connecting`, which unmounts this area
    // (RequireSession) — the reveal is gone either way, so it goes first,
    // deliberately. KeyReveal only offers this after a copy.
    setReveal(null)
    void connect(instanceUrl, plaintext)
  }

  const isSessionKey = (key: ApiKeyResponse) => identity !== null && key.id === identity.key_id

  function askRevoke(key: ApiKeyResponse) {
    setRevokeError(null)
    setConfirm({ key, stage: 'confirm' })
  }

  async function confirmRevoke() {
    if (confirm === null) return
    // The session key earns a second, distinct confirmation.
    if (confirm.stage === 'confirm' && isSessionKey(confirm.key)) {
      setConfirm({ key: confirm.key, stage: 'session' })
      return
    }
    setRevokingId(confirm.key.id)
    setRevokeError(null)
    try {
      await client.revokeApiKey(confirm.key.id)
      setConfirm(null)
      if (isSessionKey(confirm.key)) {
        // The next request would answer 401 anyway: drop the session now,
        // honestly. The shell falls to "No session" and the route guard
        // returns to the Session screen.
        disconnect()
        return
      }
      keys.refresh()
    } catch (cause) {
      setRevokeError(describeError(cause))
    } finally {
      setRevokingId(null)
    }
  }

  const confirmBlock =
    confirm !== null ? (
      <RevokeConfirm
        apiKey={confirm.key}
        stage={confirm.stage}
        busy={revokingId === confirm.key.id}
        error={revokeError}
        onConfirm={() => void confirmRevoke()}
        onKeep={() => setConfirm(null)}
      />
    ) : null

  return (
    <div className="workspace">
      <div className="page-head">
        <div className="eyebrow">
          <span className="rule" />
          Playground · Workspace
        </div>
        <h1>API keys</h1>
        <p className="page-sub">
          Keys beget keys: a key can issue keys for its own tenant and any tenant it can see. The
          issued key sees <b>only that tenant&apos;s scope</b>. Keys carry no roles yet — authority
          is tenant scope.
        </p>
      </div>

      <div className="action-row">
        <div className="f">
          <label htmlFor="keys-tenant">
            Issue a key for tenant <span className="tag">· from your key</span>
          </label>
          <input
            className="input"
            id="keys-tenant"
            type="text"
            placeholder="your tenant, or a visible subordinate"
            value={tenantInput}
            onChange={(event) => setTenantInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') loadKeys()
            }}
            spellCheck={false}
            autoCapitalize="off"
            autoCorrect="off"
          />
        </div>
        <div className="f">
          <label htmlFor="keys-label">
            Label <span className="tag">· optional</span>
          </label>
          <input
            className={issueError?.fields.some((f) => f.field === 'label') ? 'input err' : 'input'}
            id="keys-label"
            type="text"
            placeholder="what this key is for"
            value={label}
            onChange={(event) => setLabel(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void issue()
            }}
            spellCheck={false}
            autoCapitalize="off"
            autoCorrect="off"
            disabled={issuing}
          />
        </div>
        <button
          className="btn-ghost"
          type="button"
          onClick={loadKeys}
          disabled={keys.status === 'loading' || tenantInput.trim() === ''}
        >
          {keys.status === 'loading' ? 'Loading…' : 'Load keys'}
        </button>
        <button
          className="btn"
          type="button"
          onClick={() => void issue()}
          disabled={issuing || tenantInput.trim() === ''}
        >
          {issuing ? 'Issuing…' : 'Issue key'}
        </button>
        <p className="hint">
          Your own tenant, or a visible subordinate. A tenant you can&apos;t see answers 404.
        </p>

        {issueNotFound && (
          <div className="error-note quiet">
            <div className="t">No tenant is visible at that id.</div>
            <div className="d">
              A wrong id and one outside your key&apos;s scope look the same on purpose.
            </div>
          </div>
        )}
        {issueError !== null && (issueError.fields.length > 0 || issueError.message !== null) && (
          <div className="error-note" role="alert">
            {issueError.fields.map((fieldError, index) => (
              <div key={`${fieldError.field}-${index}`}>
                <div className="f">{fieldError.field}</div>
                <div className="d">{fieldError.message}</div>
              </div>
            ))}
            {issueError.message !== null && <div className="d">{issueError.message}</div>}
          </div>
        )}
      </div>

      {reveal !== null && (
        <KeyReveal
          created={reveal.created}
          tenant={reveal.tenant}
          onStored={() => setReveal(null)}
          onConnect={connectWithKey}
        />
      )}

      <div className="list">
        <div className="l-head">
          <span>Key</span>
          <span>Created</span>
          <span>Last used</span>
          <span>State</span>
          <span />
        </div>

        {keys.status === 'idle' && (
          <div className="list-empty-keys">
            <div className="t">No tenant loaded.</div>
            <div className="d">Load a tenant id above to see its keys.</div>
          </div>
        )}
        {keys.status === 'loading' && keys.keys.length === 0 && (
          <div className="list-skel" aria-label="Loading keys">
            <div className="skel" style={{ width: '62%' }} />
            <div className="skel" style={{ width: '48%' }} />
            <div className="skel" style={{ width: '55%' }} />
          </div>
        )}
        {keys.status === 'error' && (
          <div className="list-empty-keys">
            <div className="t">Could not list keys.</div>
            <div className="d">{keys.error}</div>
          </div>
        )}
        {keys.status === 'loaded' && keys.keys.length === 0 && (
          <div className="list-empty-keys">
            <div className="t">No keys for this tenant.</div>
            <div className="d">
              Issue the first one above — it&apos;s shown once,
              <br />
              so have somewhere to put it.
            </div>
          </div>
        )}

        {keys.keys.map((key) => (
          <div key={key.id}>
            <KeyRow
              apiKey={key}
              isSession={isSessionKey(key)}
              busy={revokingId !== null}
              onRevoke={() => askRevoke(key)}
            />
            {/* Inline, in the row's place — a modal is for the irrecoverable,
                and this is not. */}
            {!isMobile && confirm !== null && confirm.key.id === key.id && confirmBlock}
          </div>
        ))}

        {keys.keys.length > 0 && (
          <div className="list-note">
            Metadata only — plaintext keys are never listed. <span className="approx-mark">≈</span>{' '}
            last used is approximate (auth cold path; bounded by cache TTL). Revoke is soft: the
            row stays, dated.
          </div>
        )}
      </div>

      {isMobile && (
        <BottomSheet open={confirm !== null} onClose={() => setConfirm(null)} label="Revoke key">
          {confirmBlock}
        </BottomSheet>
      )}
    </div>
  )
}
