import { useSession } from '../../session/SessionContext'

const STATUS_TEXT = {
  disconnected: 'No session',
  connecting: 'Connecting…',
  connected: 'Connected',
  error: 'No session',
} as const

/** Sidebar foot: session status dot + hint + version chip. */
export function SessionStatusFoot() {
  const { status, instanceUrl } = useSession()
  const connected = status === 'connected'

  return (
    <div className="sidebar-foot">
      <div className="session-status">
        <span className={`status-dot ${status}`} />
        <span className="status-txt">{STATUS_TEXT[status]}</span>
      </div>
      <div className="hint">{connected ? instanceUrl : 'Connect to unlock the areas above.'}</div>
      <span className="version-chip">playground · dev</span>
    </div>
  )
}
