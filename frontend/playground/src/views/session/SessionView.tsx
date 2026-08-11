import { useState } from 'react'
import type { FormEvent } from 'react'
import { DEFAULT_INSTANCE_URL, useSession } from '../../session/SessionContext'
import { Topo } from './Topo'

/**
 * The session screen: point the playground at a running instance. Connect
 * probes GET /actuator/health through the dev proxy; because the Bearer
 * header is always attached, the probe validates the API key too.
 */
export function SessionView() {
  const { connect, status, error, instanceUrl } = useSession()
  const [url, setUrl] = useState(instanceUrl || DEFAULT_INSTANCE_URL)
  const [apiKey, setApiKey] = useState('')
  const [showKey, setShowKey] = useState(false)
  // Attempt-scoped, not session-scoped: a failed re-connect keeps the live
  // session (SessionContext restores it), so the probe line and the error
  // note must reflect THIS attempt, not the surviving session status.
  const [attempt, setAttempt] = useState<'none' | 'checking' | 'ok' | 'failed'>('none')

  const connecting = attempt === 'checking'
  const canConnect = !connecting && url.trim() !== '' && apiKey.trim() !== ''

  const probe = attempt === 'none' && status === 'connected' ? 'ok' : attempt

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canConnect) return
    setAttempt('checking')
    const ok = await connect(url, apiKey.trim())
    setAttempt(ok ? 'ok' : 'failed')
  }

  return (
    <>
      <Topo />
      <div className="session-wrap">
        <div className="eyebrow">Playground · Session</div>
        <h1>
          Point it at a running <em>instance</em>.
        </h1>
        <p className="lede">
          Everything the playground does goes through the same REST surface your clients use —
          authenticated with an API key, scoped to its tenant.
        </p>

        <form className="card" onSubmit={onSubmit} noValidate>
          <div className="field">
            <label htmlFor="url">Instance URL</label>
            <input
              className="input"
              id="url"
              type="text"
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              spellCheck={false}
              autoComplete="off"
            />
            <div className="helper">
              The Cauce API you want to exercise. The local quickstart serves here by default.
            </div>
          </div>

          <div className="field">
            <label htmlFor="key">API key</label>
            <div className="input-key">
              <input
                className="input"
                id="key"
                type={showKey ? 'text' : 'password'}
                placeholder="cauce_k_…"
                value={apiKey}
                onChange={(event) => setApiKey(event.target.value)}
                spellCheck={false}
                autoComplete="new-password"
              />
              <button
                className="reveal-btn"
                type="button"
                onClick={() => setShowKey((shown) => !shown)}
                aria-pressed={showKey}
              >
                {showKey ? 'Hide' : 'Show'}
              </button>
            </div>
            <div className="helper">
              Sent as <code>Authorization: Bearer</code> on every request. Kept in memory only —
              refreshing the page forgets it.
            </div>
          </div>

          <div className="bootstrap-hint">
            <div className="t">
              <strong>First run?</strong> The root operator key is printed once at first startup
              and can't be recovered later — look for the WARN line in the API log:
            </div>
            <span className="log-line">
              <span className="warn">WARN</span> OperatorKeyBootstrapRunner — root operator API key
              (shown once): <span className="key">cauce_k_9f2…</span>
            </span>
          </div>

          {attempt === 'failed' && error !== null && (
            <div className="error-note" role="alert">
              <div className="t">{error.message}</div>
              {error.requestId != null && (
                <span className="log-line">request_id: {error.requestId}</span>
              )}
            </div>
          )}

          <div className="connect-row">
            <button className="btn-primary" type="submit" disabled={!canConnect}>
              {connecting ? 'Connecting…' : 'Connect'}
            </button>
            <div className="probe">
              <span className="path">GET /actuator/health</span> →{' '}
              {probe === 'none' && <span className="pending">runs on connect</span>}
              {probe === 'checking' && <span className="pending">checking…</span>}
              {probe === 'ok' && <span className="ok">200 · UP</span>}
              {probe === 'failed' && <span className="fail">failed — see above</span>}
            </div>
          </div>
        </form>
      </div>
    </>
  )
}
