import { useSession } from '../../session/SessionContext'

const STATUS_TEXT = {
  disconnected: 'No session',
  connecting: 'Connecting…',
  connected: 'Connected',
  error: 'No session',
} as const

/**
 * Sidebar foot: session status dot + identity + instance + version chip.
 * When the session knows who its key is (/v1/me), the identity line shows
 * tenant name and tier above the instance URL; on instances without the
 * endpoint it simply does not appear.
 *
 * At rail width everything but the dot is hidden, so the state it carries
 * moves into `data-tip` — the foot is never mute (shell.css).
 */
export function SessionStatusFoot() {
  const { status, instanceUrl, identity } = useSession()
  const connected = status === 'connected'

  return (
    <div className="sidebar-foot">
      <div className="session-status" data-tip={connected ? instanceUrl : STATUS_TEXT[status]}>
        <span className={`status-dot ${status}`} />
        <span className="status-txt">{STATUS_TEXT[status]}</span>
      </div>
      {connected && identity !== null && (
        <div className="identity-line">
          <span className="name">{identity.tenant_name}</span> · {identity.tier}
        </div>
      )}
      <div className={connected ? 'hint mono' : 'hint'}>
        {connected ? instanceUrl : 'Connect to unlock the areas above.'}
      </div>
      <span className="version-chip">playground · dev</span>
    </div>
  )
}
