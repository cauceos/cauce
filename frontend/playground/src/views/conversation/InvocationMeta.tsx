import { formatDuration } from '../../lib/format'
import type { InvocationMetaInfo } from './thread-model'
import { shortId } from './useConversationMachine'

/**
 * The invocation meta line: outcome, how long it took, and the invocation
 * id as the correlation handle.
 *
 * Every field comes from the invocation's own wire row — the duration from
 * its `created_at`/`completed_at`. The model is deliberately absent: it is
 * not on that row, and deducing it from the agent a send targeted would
 * name the wrong model the moment you switch agents mid-thread.
 *
 * FAILED reads in slate: the palette has no danger colour.
 */
export function InvocationMeta({ meta }: { meta: InvocationMetaInfo }) {
  const ok = meta.status === 'COMPLETED'
  return (
    <div className="meta-line">
      <span className={ok ? 'ok' : 'bad'}>
        {meta.status}
        {meta.failureReason !== null && ` · ${meta.failureReason}`}
      </span>
      {meta.durationMs !== null && <span>{formatDuration(meta.durationMs)}</span>}
      <span>invocation {shortId(meta.invocationId)}</span>
    </div>
  )
}
