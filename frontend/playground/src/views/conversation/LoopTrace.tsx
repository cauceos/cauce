import { useState } from 'react'
import type { MessageResponse } from '../../api/types'
import type { ThreadItem } from './thread-model'

type LoopItem = Extract<ThreadItem, { kind: 'loop' }>

/**
 * The screen's signature: a maximal run of consecutive TOOL_CALL /
 * TOOL_RESULT messages, rendered as one collapsible trace block.
 *
 * `tool_content` (when the wire carries it) supplies the structured view
 * the mockup specifies: `args {…}` on the call, the result's tool name and
 * `is_error` flag. Messages without it (older instances) fall back to the
 * flattened `content`. Rounds are still not derivable from the wire (two
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
            {message.role === 'TOOL_CALL' ? <CallCard message={message} /> : <ResultCard message={message} />}
          </div>
        ))}
    </div>
  )
}

function CallCard({ message }: { message: MessageResponse }) {
  const tool = message.tool_content
  return (
    <>
      <span className="k">TOOL_CALL</span> · {tool?.tool_name ?? message.content}
      {tool !== undefined && (
        <>
          <br />
          <span className="payload">args {formatArgs(tool.input)}</span>
        </>
      )}
    </>
  )
}

function ResultCard({ message }: { message: MessageResponse }) {
  const tool = message.tool_content
  return (
    <>
      <span className="r">TOOL_RESULT</span>
      {tool !== undefined && (
        <>
          {' '}
          · {tool.tool_name} ·{' '}
          <span className={tool.is_error === true ? 'flag err' : 'flag'}>
            is_error: {tool.is_error === true ? 'true' : 'false'}
          </span>
        </>
      )}
      <br />
      {/* `content` already flattens the result output (with "[empty
          result]" for blank), so it stays the payload either way. */}
      <span className="payload">{message.content}</span>
    </>
  )
}

/** `args {}` when empty (the mockup's literal), pretty-printed otherwise. */
function formatArgs(input: Record<string, unknown> | undefined): string {
  if (input === undefined || Object.keys(input).length === 0) return '{}'
  return JSON.stringify(input, null, 2)
}
