import { useLayoutEffect, useRef } from 'react'
import type { InvocationStatus } from '../../api/types'
import type { ThreadItem } from './thread-model'
import { InvocationMeta } from './InvocationMeta'
import { LoopTrace } from './LoopTrace'
import { MessageBubble } from './MessageBubble'
import { PendingCard } from './PendingCard'

const NEAR_BOTTOM_PX = 80

export function Thread({
  items,
  hiddenMessageCount,
  identityRef,
  agentName,
  live,
  stopped,
  stopReason,
  fatalNote,
  hasConversation,
  onLoadEarlier,
  onResume,
}: {
  items: ThreadItem[]
  hiddenMessageCount: number
  identityRef: string
  agentName: string
  live: { invocationId: string; status: InvocationStatus } | null
  stopped: boolean
  stopReason: 'cap' | 'poll-failures' | null
  fatalNote: string | null
  hasConversation: boolean
  onLoadEarlier(): void
  onResume(): void
}) {
  const scrollerRef = useRef<HTMLDivElement>(null)
  // Follow the tail only while the user is already near it; a reader
  // scrolled up must not be yanked down by a poll append.
  const nearBottomRef = useRef(true)
  // Set by the Load-earlier click; the layout effect compensates scrollTop
  // by the height the revealed items added, so the viewport does not jump.
  const restoreRef = useRef<{ scrollTop: number; scrollHeight: number } | null>(null)

  useLayoutEffect(() => {
    const scroller = scrollerRef.current
    if (scroller === null) return
    const restore = restoreRef.current
    if (restore !== null) {
      restoreRef.current = null
      scroller.scrollTop = restore.scrollTop + (scroller.scrollHeight - restore.scrollHeight)
      return
    }
    if (nearBottomRef.current) {
      scroller.scrollTop = scroller.scrollHeight
    }
  }, [items, live, stopped, fatalNote])

  return (
    <div
      className="thread"
      ref={scrollerRef}
      onScroll={() => {
        const scroller = scrollerRef.current
        if (scroller === null) return
        nearBottomRef.current =
          scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < NEAR_BOTTOM_PX
      }}
    >
      <div className="thread-inner">
        {!hasConversation && items.length === 0 && live === null && (
          <div className="thread-empty">
            No conversation yet. Pick an agent and send the first message — the backend resolves
            or creates the OPEN conversation for the identity ref.
          </div>
        )}

        {hiddenMessageCount > 0 && (
          <button
            className="load-earlier"
            type="button"
            onClick={() => {
              const scroller = scrollerRef.current
              if (scroller !== null) {
                restoreRef.current = {
                  scrollTop: scroller.scrollTop,
                  scrollHeight: scroller.scrollHeight,
                }
              }
              onLoadEarlier()
            }}
          >
            Load earlier messages ({hiddenMessageCount})
          </button>
        )}

        {items.map((item) => {
          switch (item.kind) {
            case 'loop':
              return <LoopTrace key={item.key} item={item} />
            case 'meta-only':
              return (
                <div className="msg agent meta-only" key={item.key}>
                  <InvocationMeta meta={item.meta} />
                </div>
              )
            default:
              return (
                <MessageBubble
                  key={item.message.id}
                  item={item}
                  identityRef={identityRef}
                  agentName={agentName}
                />
              )
          }
        })}

        {live !== null && (
          <PendingCard live={live} stopped={stopped} stopReason={stopReason} onResume={onResume} />
        )}

        {fatalNote !== null && (
          <div className="msg system">
            <span className="who">Playground</span>
            <div className="bubble">{fatalNote}</div>
          </div>
        )}
      </div>
    </div>
  )
}
