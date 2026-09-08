import { useEffect, useState } from 'react'
import type { InvocationStatus } from '../../api/types'
import { shortId } from './useConversationMachine'

/**
 * The in-flight state: honest polling, no fake streaming. Rendered where
 * the reply will appear.
 *
 * When polling stops (the 5-minute cap or repeated failures) the invocation
 * keeps running server-side — the cap protects the browser, not the work.
 * Resume restarts the same 2s cadence against the same invocation.
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
  const elapsed = useElapsedSeconds(!stopped)

  if (stopped) {
    return (
      <div className="fail quiet">
        <div className="m">
          {stopReason === 'cap'
            ? 'Still pending server-side after 5:00 of polling.'
            : 'Polling stopped after repeated failures.'}
        </div>
        <div className="n">
          Polling paused — the invocation is untouched.
          <button className="btn-ghost sm" type="button" onClick={onResume}>
            Resume polling
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="pending">
      <div className="row">
        <span className="pdot" aria-hidden="true" />
        invocation {live.status.toLowerCase()} · {shortId(live.invocationId)} · polling every 2s ·{' '}
        {formatClock(elapsed)}
      </div>
    </div>
  )
}

/**
 * Seconds since this card appeared. Purely a readout: it never drives the
 * poll, which the machine schedules on its own clock.
 */
function useElapsedSeconds(running: boolean): number {
  const [seconds, setSeconds] = useState(0)
  useEffect(() => {
    if (!running) return
    const started = Date.now()
    const timer = window.setInterval(
      () => setSeconds(Math.floor((Date.now() - started) / 1000)),
      1000,
    )
    return () => window.clearInterval(timer)
  }, [running])
  return seconds
}

function formatClock(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60)
  return `${minutes}:${String(totalSeconds % 60).padStart(2, '0')}`
}
