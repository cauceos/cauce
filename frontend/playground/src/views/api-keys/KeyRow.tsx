import type { ApiKeyResponse } from '../../api/types'
import { formatUtc, shortId, utcDay } from '../../lib/format'

/**
 * One key, metadata only. The primary text is the label when one was
 * given, else the wire's key_prefix — the eight characters a holder can
 * recognise the key by. Nothing is invented for a nameless key: the short
 * id stays as the secondary line either way.
 *
 * last_used_at is APPROXIMATE (auth cold path only; cache hits skip it;
 * staleness bounded by the cache TTL) and is never rendered as exact: the
 * "≈" and its hover note say so.
 */
export function KeyRow({
  apiKey,
  isSession,
  busy,
  onRevoke,
}: {
  apiKey: ApiKeyResponse
  /** The key this session is connected with (from /v1/me). */
  isSession: boolean
  busy: boolean
  onRevoke(): void
}) {
  const active = apiKey.status === 'ACTIVE'
  return (
    <div className={active ? 'row' : 'row dimmed'}>
      <div className="kmain">
        <div className="id">
          {/* A label reads as a name; the prefix reads as what it is, mono. */}
          <span className={apiKey.label !== null ? 'primary' : 'primary mono'}>
            {apiKey.label ?? apiKey.key_prefix}
          </span>
          <span className="secondary">{shortId(apiKey.id)}</span>
          {isSession && <span className="chip teal me">this session</span>}
        </div>
        <div className="sub2">
          <span className="t created">
            <span className="lbl">created </span>
            {formatUtc(apiKey.created_at)}
          </span>
          <span className="t used">
            <span className="lbl">last used </span>
            {apiKey.last_used_at !== null ? (
              <span
                className="approx"
                title="Approximate: updated on the auth cold path only; cache hits don't touch it. Staleness is bounded by the cache TTL."
              >
                ≈ {formatUtc(apiKey.last_used_at)}
              </span>
            ) : (
              <span className="approx" title="Never used yet — or only through the cache, which does not record use.">
                —
              </span>
            )}
          </span>
        </div>
      </div>

      <span className="state">
        <StatusChip apiKey={apiKey} />
      </span>

      {/* Revoke is soft: the row stays, dated. A key that is not ACTIVE has
          nothing left to revoke, and the button says which state it is in. */}
      <button className="btn-ghost sm rev" type="button" disabled={!active || busy} onClick={onRevoke}>
        {active ? 'Revoke…' : apiKey.status === 'REVOKED' ? 'Revoked' : 'Expired'}
      </button>
    </div>
  )
}

/** Status verbatim from the wire: ACTIVE moss; REVOKED and EXPIRED quiet, with their date. */
function StatusChip({ apiKey }: { apiKey: ApiKeyResponse }) {
  if (apiKey.status === 'ACTIVE') return <span className="chip moss">ACTIVE</span>
  const when = apiKey.status === 'REVOKED' ? apiKey.revoked_at : apiKey.expires_at
  const day = when !== null ? utcDay(when) : null
  return (
    <span className="chip quiet">
      {apiKey.status}
      {day !== null && ` · ${day}`}
    </span>
  )
}
