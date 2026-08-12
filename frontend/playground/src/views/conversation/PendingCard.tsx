import type { InvocationStatus } from '../../api/types'
import { shortId } from './useConversationMachine'

/**
 * The in-flight state: honest polling, no fake streaming. When polling
 * stops (5-minute cap or repeated failures) the invocation keeps running
 * server-side; Resume restarts the poll from where it left off.
 */
export function PendingCard({
  live,
  stopped,
  stopReason,
  onResume,
}: {
  live: { invocationId: string; status: InvocationStatus }
  stopped: boolean
  stopReason: 'cap' | 'poll-failures' | null
  onResume(): void
}) {
  return (
    <div className="pending-row">
      <div className="pending-card">
        {!stopped && <span className="pulse" aria-hidden="true" />}
        {stopped ? 'Still processing server-side — polling stopped.' : 'Agent is working…'}
      </div>
      <span className="meta">
        invocation {shortId(live.invocationId)} · {live.status}
        {stopped
          ? stopReason === 'cap'
            ? ' · polling stopped after 5m'
            : ' · polling stopped after repeated failures'
          : ' · polling every 2s'}
      </span>
      {stopped && (
        <button className="resume-btn" type="button" onClick={onResume}>
          Resume polling
        </button>
      )}
    </div>
  )
}
