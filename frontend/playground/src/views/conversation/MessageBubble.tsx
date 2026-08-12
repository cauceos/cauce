import type { ThreadItem } from './thread-model'
import { InvocationMeta } from './InvocationMeta'

type BubbleItem = Extract<ThreadItem, { kind: 'user' | 'agent' | 'system' }>

export function MessageBubble({
  item,
  identityRef,
  agentName,
}: {
  item: BubbleItem
  identityRef: string
  agentName: string
}) {
  if (item.kind === 'user') {
    return (
      <div className="msg user">
        <span className="who">You · {identityRef}</span>
        <div className="bubble">{item.message.content}</div>
      </div>
    )
  }
  if (item.kind === 'system') {
    return (
      <div className="msg system">
        <span className="who">System</span>
        <div className="bubble">{item.message.content}</div>
      </div>
    )
  }
  return (
    <div className="msg agent">
      <span className="who">Agent · {agentName}</span>
      <div className="bubble">{item.message.content}</div>
      {item.meta !== null && <InvocationMeta meta={item.meta} />}
    </div>
  )
}
