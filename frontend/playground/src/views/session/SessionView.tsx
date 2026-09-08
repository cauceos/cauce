import { useState } from 'react'
import type { FormEvent } from 'react'
import { DEFAULT_INSTANCE_URL, useSession } from '../../session/SessionContext'
import { Topo } from './Topo'

/**
 * The session screen: point the playground at a running instance.
 *
 * Connect issues ONE authenticated `GET /actuator/health`, and that single
 * call decides the outcome. The path is public, but the auth filter rejects
 * a bad Bearer before the chain reaches it, so the probe validates the key
 * AND the address at once — which is why 401 and 502 are different facts and
 * read differently: 401 means something answered and said no (and carries a
 * request_id), 502 means nothing answered (so there is no request_id to
 * show — absent, not faked). A successful connect then fetches /v1/me
 * best-effort for the identity line; that call never decides the outcome.
 *
 * Four states: idle · connecting · rejected · unreachable.
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
  const failed = attempt === 'failed' && error !== null
  // Which field is implicated: a rejected key marks the key, an address that
  // answered nothing marks the URL. Anything else marks neither.
  const keyRejected = failed && error.kind === 'unauthorized'
  const urlRejected = failed && (error.kind === 'unreachable' || error.kind === 'invalid-url')

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canConnect) return
    setAttempt('checking')
    const ok = await connect(url, apiKey.trim())
    setAttempt(ok ? 'ok' : 'failed')
  }

  return (
    <div className="session-screen">
      <div className="glow" aria-hidden="true" />
      <Topo />

      <div className="stage">
        <div className="split">
          {/* LEFT — the statement, and how to read the outcome. */}
          <div className="say">
            <div className="eyebrow">
              <span className="rule" />
              Playground · Session
            </div>
            <h1>
              Point it at a running <em>instance</em>.
            </h1>
            <p className="lede">
              Everything here goes through the same REST surface your own clients use — one API
              key, scoped to its tenant, sent on every request.
            </p>

            <div className="legend">
              <span className="k">Connect sends one authenticated health check</span>
              <div className="row">
                <span className="code ok">200</span>
                <span className="t">
                  <span className="t-long">
                    The instance answered and accepted the key. Every area unlocks.
                  </span>
                  <span className="t-short">key accepted — everything unlocks</span>
                </span>
              </div>
              <div className="row">
                <span className="code bad">401</span>
                <span className="t">
                  <span className="t-long">It answered, and rejected the key.</span>
                  <span className="t-short">answered, key rejected</span>
                </span>
              </div>
              <div className="row">
                <span className="code bad">502</span>
                <span className="t">
                  <span className="t-long">Nothing answered at that address.</span>
                  <span className="t-short">nothing answered</span>
                </span>
              </div>
            </div>
          </div>

          {/* RIGHT — the card. */}
          <form className="card" onSubmit={onSubmit} noValidate>
            <div className="field">
              <div className="f-top">
                <label htmlFor="url">Instance URL</label>
                <span className="req">required</span>
              </div>
              <div className="input-wrap">
                <svg
                  className="lead"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2}
                  strokeLinecap="round"
                  aria-hidden="true"
                >
                  <rect x="3" y="4" width="18" height="7" rx="2" />
                  <rect x="3" y="13" width="18" height="7" rx="2" />
                  <path d="M7 7.5h.01M7 16.5h.01" />
                </svg>
                <input
                  className={urlRejected ? 'input has-lead err' : 'input has-lead'}
                  id="url"
                  type="url"
                  inputMode="url"
                  value={url}
                  onChange={(event) => setUrl(event.target.value)}
                  spellCheck={false}
                  autoCapitalize="off"
                  autoCorrect="off"
                  autoComplete="off"
                />
              </div>
              <p className="helper">
                Origin only — no path. The local quickstart serves here by default.
              </p>
            </div>

            <div className="field">
              <div className="f-top">
                <label htmlFor="key">API key</label>
                <span className="req">required</span>
              </div>
              <div className="input-wrap">
                <svg
                  className="lead"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2}
                  strokeLinecap="round"
                  aria-hidden="true"
                >
                  <circle cx="8" cy="14" r="4" />
                  <path d="M11 11l8-8M17 4l3 3M14 7l2 2" />
                </svg>
                <input
                  className={keyRejected ? 'input has-lead has-eye err' : 'input has-lead has-eye'}
                  id="key"
                  type={showKey ? 'text' : 'password'}
                  placeholder="ck_…"
                  value={apiKey}
                  onChange={(event) => setApiKey(event.target.value)}
                  spellCheck={false}
                  autoCapitalize="off"
                  autoCorrect="off"
                  autoComplete="new-password"
                />
                {/* One control, two presentations: a labelled ghost button
                    beside the field on desktop, a 40px icon button inside it
                    below 700px, where the field needs the width more. */}
                <button
                  className="ghost-btn"
                  type="button"
                  onClick={() => setShowKey((shown) => !shown)}
                  aria-pressed={showKey}
                  aria-label={showKey ? 'Hide the API key' : 'Show the API key'}
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={2}
                    strokeLinecap="round"
                    aria-hidden="true"
                  >
                    <path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7z" />
                    <circle cx="12" cy="12" r="3" />
                  </svg>
                  <span className="ghost-label">{showKey ? 'Hide' : 'Show'}</span>
                </button>
              </div>
              <p className="helper">
                Sent as <code>Authorization: Bearer</code>. Held in memory only — reloading the
                page forgets it.
              </p>
            </div>

            <div className="bootstrap-hint">
              <div className="hint-head">
                <span className="k">API log</span>
                <span className="t">
                  <strong>First run?</strong> The root operator key is printed once and can&apos;t
                  be recovered.
                </span>
              </div>
              <span className="log-line">
                <span className="warn">WARN</span> OperatorKeyBootstrapRunner — Bootstrapped
                operator. Store its API key now: <span className="key">ck_9f2…</span>
              </span>
            </div>

            {failed && (
              <div className="error-note" role="alert">
                <div className="t">{error.message}</div>
                {error.detail != null && <div className="d">{error.detail}</div>}
                {/* Only when something actually answered: a dead address
                    produces no envelope, so there is no id to print. */}
                {error.requestId != null && (
                  <span className="rid">request_id {error.requestId}</span>
                )}
              </div>
            )}

            <div className="connect-row">
              {/* Disabled with a spinner while the probe runs; the fields stay
                  editable and nothing overlays the card. */}
              <button className="btn" type="submit" disabled={!canConnect}>
                {connecting ? (
                  <>
                    <svg
                      className="spin"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={2.4}
                      strokeLinecap="round"
                      aria-hidden="true"
                    >
                      <path d="M21 12a9 9 0 1 1-6.2-8.6" />
                    </svg>
                    Connecting
                  </>
                ) : (
                  <>
                    Connect
                    <svg
                      className="arw"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={2.4}
                      strokeLinecap="round"
                      aria-hidden="true"
                    >
                      <path d="M5 12h13M13 6l6 6-6 6" />
                    </svg>
                  </>
                )}
              </button>
              <span className="kbd" aria-hidden="true">
                ⏎
              </span>
              <div className="probe">
                <span className="path">GET /actuator/health</span> →{' '}
                {probe === 'none' && <span className="pending">runs on connect</span>}
                {probe === 'checking' && <span className="pending">checking…</span>}
                {probe === 'ok' && <span className="ok">200 · UP</span>}
                {probe === 'failed' && (
                  <span className="fail">
                    {error !== null && error.status != null ? error.status : 'failed'}
                  </span>
                )}
              </div>
            </div>
          </form>
        </div>
      </div>
    </div>
  )
}
