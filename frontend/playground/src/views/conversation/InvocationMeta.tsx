import type { InvocationMetaInfo } from './thread-model'
import { shortId } from './useConversationMachine'

/**
 * The invocation meta line — COMPLETED in moss, FAILED with its public
 * classification in the quiet palette (no danger color exists and none is
 * invented, per the session screen's precedent).
 */
export function InvocationMeta({ meta }: { meta: InvocationMetaInfo }) {
  const ok = meta.status === 'COMPLETED'
  return (
    <span className="meta">
      invocation {shortId(meta.invocationId)} ·{' '}
      <span className={ok ? 'ok' : 'bad'}>
        {meta.status}
        {meta.failureReason !== null && ` · ${meta.failureReason}`}
      </span>
    </span>
  )
}
