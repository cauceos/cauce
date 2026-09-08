import type { ApiKeyResponse } from '../../api/types'
import { shortId } from '../../lib/format'

/**
 * The revoke confirmation, inline in the list (desktop, tablet) or inside
 * a bottom sheet (mobile — allowed there because it is recoverable: the
 * backdrop means "keep it").
 *
 * Two stages. Every key gets the first. The key this session is connected
 * with then gets a SECOND, distinct one: revoking it ends the session on
 * the spot. Slate framing — no danger colour is invented — and the primary
 * is neutral-bright rather than teal, because revoking is not the happy
 * path.
 */
export function RevokeConfirm({
  apiKey,
  stage,
  busy,
  error,
  onConfirm,
  onKeep,
}: {
  apiKey: ApiKeyResponse
  stage: 'confirm' | 'session'
  busy: boolean
  error: string | null
  onConfirm(): void
  onKeep(): void
}) {
  const name = apiKey.label ?? apiKey.key_prefix
  return (
    <div className={stage === 'session' ? 'confirm danger' : 'confirm'} role="alertdialog" aria-live="assertive">
      {stage === 'confirm' ? (
        <>
          <div className="t">
            Revoke {name} · {shortId(apiKey.id)}?
          </div>
          <div className="d">
            Every client using it gets <code>401</code> on its next request. Soft revoke — the row
            stays, dated. This can&apos;t be undone.
          </div>
        </>
      ) : (
        <>
          <div className="t">This is the key you&apos;re connected with.</div>
          <div className="d">
            Revoking it ends this session immediately — the next request answers <code>401</code>.
            Issue another key first if you still need access.
          </div>
        </>
      )}
      <div className="acts">
        <button className="btn neutral" type="button" disabled={busy} onClick={onConfirm}>
          {busy ? 'Revoking…' : stage === 'session' ? 'Revoke anyway' : 'Revoke'}
        </button>
        <button className="btn-ghost" type="button" disabled={busy} onClick={onKeep}>
          Keep it
        </button>
      </div>
      {error !== null && <div className="err">{error}</div>}
    </div>
  )
}
