import { useState } from 'react'
import type { ThreadItem } from './thread-model'

type LoopItem = Extract<ThreadItem, { kind: 'loop' }>

/**
 * The screen's signature: a maximal run of consecutive TOOL_CALL /
 * TOOL_RESULT messages, rendered as one collapsible trace block.
 *
 * Honesty note: the wire carries only `content` — the tool name for a
 * call, the output text for a result. The mockup's `args {}`, `is_error`
 * and the result's tool name are not exposed by the API and are not
 * fabricated here (exposing tool_content on the message DTO is a
 * registered future backend unit). Rounds are not derivable either (two
 * calls in one round and two one-call rounds look identical), so the
 * summary counts tool calls only.
 */
export function LoopTrace({ item }: { item: LoopItem }) {
  // Expanded by default — the mockup only draws the open state.
  const [open, setOpen] = useState(true)
  return (
    <div className="loop-trace">
      <button
        className="loop-summary"
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((wasOpen) => !wasOpen)}
      >
        <span className="tri">{open ? '▼' : '▶'}</span> agent loop · {item.toolCallCount} tool{' '}
        {item.toolCallCount === 1 ? 'call' : 'calls'}
      </button>
      {open &&
        item.messages.map((message) => (
          <div className="tool-card" key={message.id}>
            {message.role === 'TOOL_CALL' ? (
              <>
                <span className="k">TOOL_CALL</span> · {message.content}
              </>
            ) : (
              <>
                <span className="r">TOOL_RESULT</span>
                <br />
                <span className="payload">{message.content}</span>
              </>
            )}
          </div>
        ))}
    </div>
  )
}
