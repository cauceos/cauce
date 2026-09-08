import { Fragment, useLayoutEffect, useRef } from 'react'
import type { InvocationStatus, MessageResponse } from '../../api/types'
import { utcDay } from '../../lib/format'
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
  sessionAnchorMessageId,
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
  /** Id of the first message this session sent; null when it sent none. */
  sessionAnchorMessageId: string | null
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

  const todayUtc = utcDay(new Date().toISOString())
  // Day separators and the session marker are decided here, at render: both
  // are derived from data already in hand, so the thread model stays a pure
  // function of the wire.
  let lastDay: string | null = null
  let sessionMarkDone = sessionAnchorMessageId === null

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
            <div className="t">Nothing here yet.</div>
            <div className="d">
              Pick an agent and send the first message —<br />
              the loop will be visible when it happens.
            </div>
          </div>
        )}

        {hiddenMessageCount > 0 && (
          <div className="load-earlier-row">
            <button
              className="btn-ghost sm mono"
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
              ↑ Load earlier · {hiddenMessageCount} buffered
            </button>
          </div>
        )}

        {items.map((item, index) => {
          const anchorMessage = leadMessage(item)
          const day = anchorMessage === null ? null : utcDay(anchorMessage.created_at)
          const dayChanged = day !== null && day !== lastDay
          if (dayChanged) lastDay = day

          // The marker goes in once, immediately before the first message
          // this session sent — and only when something precedes it, so a
          // thread that is entirely ours never gets one. Passing the anchor
          // ends the search either way.
          let marksSession = false
          if (
            !sessionMarkDone &&
            anchorMessage !== null &&
            sessionAnchorMessageId !== null &&
            anchorMessage.id >= sessionAnchorMessageId
          ) {
            marksSession = index > 0 || hiddenMessageCount > 0
            sessionMarkDone = true
          }

          return (
            <Fragment key={itemKey(item)}>
              {dayChanged && (
                <div className="day">{day === todayUtc ? `Today · ${day}` : day}</div>
              )}
              {marksSession && (
                <div className="session-mark">sent from this session · invocation meta below</div>
              )}
              {renderItem(item, identityRef, agentName)}
            </Fragment>
          )
        })}

        {live !== null && (
          <PendingCard live={live} stopped={stopped} stopReason={stopReason} onResume={onResume} />
        )}

        {fatalNote !== null && (
          <div className="fail quiet">
            <div className="m">{fatalNote}</div>
            <div className="n">The invocation is untouched — only this browser stopped looking.</div>
          </div>
        )}
      </div>
    </div>
  )
}

function renderItem(item: ThreadItem, identityRef: string, agentName: string) {
  switch (item.kind) {
    case 'loop':
      return <LoopTrace item={item} />
    case 'meta-only':
      return (
        <div className="msg agent meta-only">
          <InvocationMeta meta={item.meta} />
        </div>
      )
    default:
      return <MessageBubble item={item} identityRef={identityRef} agentName={agentName} />
  }
}

/** The message a row is dated by; meta-only rows carry none of their own. */
function leadMessage(item: ThreadItem): MessageResponse | null {
  if (item.kind === 'loop') return item.messages[0] ?? null
  if (item.kind === 'meta-only') return null
  return item.message
}

function itemKey(item: ThreadItem): string {
  return item.kind === 'loop' || item.kind === 'meta-only' ? item.key : item.message.id
}
