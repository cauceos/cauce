import { useState } from 'react'
import type { ApiKeyCreatedResponse, TenantResponse } from '../../api/types'
import { formatUtc } from '../../lib/format'
import { useClipboard } from '../../lib/useClipboard'

/**
 * THE REVEAL — the only irrecoverable moment in the playground. The server
 * keeps a hash; nothing can show this plaintext again.
 *
 * Therefore, by design and without exception: never inside a sheet, a
 * modal with a backdrop, a toast, or anything with a timeout. Inline in the
 * page flow on every viewport, and the ONLY way to make it go away is the
 * explicit "I've stored it". Navigating away loses it (memory only) — the
 * panel says so.
 *
 * "Connect with this key" is client behaviour, not API: it re-runs the
 * session connect with this plaintext. That reconnect passes through
 * `connecting`, which unmounts every locked area (RequireSession) — this
 * panel included — so it is gated on having copied the key at least once.
 * You cannot switch to a key you have not put somewhere.
 */
export function KeyReveal({
  created,
  tenant,
  onStored,
  onConnect,
}: {
  created: ApiKeyCreatedResponse
  /** Best-effort: null when the tenant could not be read back. */
  tenant: TenantResponse | null
  onStored(): void
  onConnect(plaintext: string): void
}) {
  const { copied, copy } = useClipboard(1500)
  const [copiedOnce, setCopiedOnce] = useState(false)
  // The wire's key_prefix is the first eight characters of the plaintext —
  // the same eight the list identifies the key by. Colouring exactly those
  // ties the reveal to its row.
  const prefix = created.key_prefix
  const rest = created.api_key.startsWith(prefix) ? created.api_key.slice(prefix.length) : created.api_key

  return (
    <section className="reveal" aria-live="polite">
      <div className="r-eyebrow">
        <span className="k">
          Issued · {tenant !== null ? tenant.name : created.tenant_id.slice(0, 8) + '…'}
        </span>
        <span className="once">shown once</span>
      </div>
      <div className="r-title">
        Store it now. It <em>won&apos;t</em> come back.
      </div>
      <p className="r-sub">
        The server keeps only a hash of this key. Nothing — not the API, not this screen, not a
        reload — can show it again. Leaving this page forgets it.
      </p>

      <div className="keybox">
        <span className="key">
          <span className="pfx">{created.api_key.startsWith(prefix) ? prefix : ''}</span>
          {rest}
        </span>
        <button
          className={copied ? 'btn-ghost copybtn copied' : 'btn-ghost copybtn'}
          type="button"
          onClick={() => {
            copy(created.api_key)
            setCopiedOnce(true)
          }}
        >
          {copied ? (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" aria-hidden="true">
              <path d="M5 13l4 4L19 7" />
            </svg>
          ) : (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" aria-hidden="true">
              <rect x="9" y="9" width="11" height="11" rx="2" />
              <path d="M5 15V5a2 2 0 0 1 2-2h10" />
            </svg>
          )}
          {copied ? 'Copied' : 'Copy key'}
        </button>
      </div>

      <div className="r-meta">
        <span>
          key_id <span className="v">{created.id}</span>
        </span>
        {created.label !== null && (
          <span>
            label <span className="v">{created.label}</span>
          </span>
        )}
        <span>
          tenant{' '}
          <span className="v">
            {tenant !== null ? `${tenant.name} · ${tenant.tier}` : created.tenant_id}
          </span>
        </span>
        <span>
          issued <span className="v">{formatUtc(created.created_at)}</span>
        </span>
      </div>

      <div className="r-foot">
        <button className="btn neutral" type="button" onClick={onStored}>
          I&apos;ve stored it
        </button>
        <button
          className="btn-ghost"
          type="button"
          disabled={!copiedOnce}
          title={copiedOnce ? undefined : 'Copy the key first — reconnecting dismisses this panel'}
          onClick={() => onConnect(created.api_key)}
        >
          Connect with this key
        </button>
        <span className="warn">
          <b>This button is the only way to dismiss.</b> No backdrop, no timeout, no accidental
          close. Connecting with the key dismisses it too — copy first.
        </span>
      </div>
    </section>
  )
}
