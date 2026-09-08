import { utcTime } from '../../lib/format'
import type { ThreadItem } from './thread-model'
import { InvocationMeta } from './InvocationMeta'

type BubbleItem = Extract<ThreadItem, { kind: 'user' | 'agent' | 'system' }>

/**
 * A thread row. Only the user gets a bubble — the agent reply is the
 * content of the page, so it reads as body text rather than a quoted aside.
 *
 * SYSTEM messages are the backend's `[orchestration_error]` writes: they
 * carry the provider's own words and are rendered verbatim, in slate. The
 * palette has no danger colour and none is invented.
 */
export function MessageBubble({
  item,
  identityRef,
  agentName,
}: {
  item: BubbleItem
  identityRef: string
  agentName: string
}) {
  const when = utcTime(item.message.created_at)

  if (item.kind === 'user') {
    return (
      <div className="msg user">
        <div className="who">
          <span className="when">{when}</span>
          <span>You · {identityRef}</span>
        </div>
        <div className="bubble">{item.message.content}</div>
      </div>
    )
  }

  if (item.kind === 'system') {
    return (
      <div className="fail">
        <div className="t">
          <span className="chip bad">SYSTEM</span>
          <span className="when">{when}</span>
        </div>
        <div className="m">{item.message.content}</div>
      </div>
    )
  }

  return (
    <div className="msg agent">
      <div className="who">
        <span>{agentName}</span>
        <span className="when">{when}</span>
      </div>
      <div className="body">{item.message.content}</div>
      {/* Present only for invocations this session witnessed — a walked
          historical reply has no meta and that is honest, not missing. */}
      {item.meta !== null && <InvocationMeta meta={item.meta} />}
    </div>
  )
}
