import type { MessageResponse } from '../../api/types'
import { utcTime } from '../../lib/format'
import type { ThreadItem } from './thread-model'
import { InvocationMeta } from './InvocationMeta'

type BubbleItem = Extract<ThreadItem, { kind: 'user' | 'agent' | 'system' }>

/**
 * A thread row. Only the user gets a bubble — the agent reply is the
 * content of the page, so it reads as body text rather than a quoted aside.
 *
 * A FALLBACK message is what the writer received after a terminal failure:
 * the agent's (or the platform's) fallback text plus the public reason. It
 * shares the failure card with SYSTEM, which only an older instance still
 * lists — current ones keep the internal error record off the API.
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
    const voice = voiceNote(item.message)
    return (
      <div className="msg user">
        <div className="who">
          <span className="when">{when}</span>
          <span>You · {identityRef}</span>
        </div>
        <div className="bubble">
          {voice !== null && (
            <div className="voice-note mono">
              voice note{voice.seconds !== undefined && ` · ${voice.seconds} s`}
              {voice.transcribed ? ' · transcribed' : ' · not transcribed yet'}
            </div>
          )}
          {item.message.content}
        </div>
      </div>
    )
  }

  if (item.kind === 'system') {
    return (
      <div className="fail">
        <div className="t">
          <span className="chip bad">{item.message.role}</span>
          {item.message.failure_reason !== undefined && (
            <span className="chip quiet">{item.message.failure_reason}</span>
          )}
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

/**
 * A voice note's facts (ADR 0012): the message's `content` is already its
 * transcript, or "[voice note]" until it has one, so only the label is added.
 */
function voiceNote(message: MessageResponse): { seconds?: number; transcribed: boolean } | null {
  const part = message.parts?.find((p) => p.type === 'audio')
  if (part === undefined || part.type !== 'audio') return null
  return { seconds: part.duration_seconds, transcribed: part.transcript !== undefined }
}
