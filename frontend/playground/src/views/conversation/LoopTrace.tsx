import { useState } from 'react'
import type { MessageResponse } from '../../api/types'
import type { ThreadItem } from './thread-model'

type LoopItem = Extract<ThreadItem, { kind: 'loop' }>

/**
 * The screen's signature: a maximal run of consecutive TOOL_CALL /
 * TOOL_RESULT messages, rendered as one collapsible trace block.
 *
 * `tool_content` supplies the structured view — `input` on the call,
 * `output` + `is_error` on the result. Messages without it (older
 * instances) fall back to the flattened `content`; arguments are never
 * invented to fill the gap.
 *
 * There is no "round N" label: two calls in one round and two one-call
 * rounds are identical on the wire, so the summary counts tool calls only.
 */
export function LoopTrace({ item }: { item: LoopItem }) {
  const [open, setOpen] = useState(true)
  return (
    <div className={open ? 'loop open' : 'loop'}>
      <button
        className="loop-h"
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((wasOpen) => !wasOpen)}
      >
        <svg
          className="chev"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={2.4}
          strokeLinecap="round"
          aria-hidden="true"
        >
          <path d="M9 6l6 6-6 6" />
        </svg>
        <span className="t">
          agent loop · {item.toolCallCount} tool {item.toolCallCount === 1 ? 'call' : 'calls'}
        </span>
        {/* The mark's three strata: the loop is the brand's own shape. */}
        <span className="strata" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
      </button>

      {open && (
        <div className="loop-body">
          {item.messages.map((message) =>
            message.role === 'TOOL_CALL' ? (
              <CallCard key={message.id} message={message} />
            ) : (
              <ResultCard key={message.id} message={message} />
            ),
          )}
        </div>
      )}
    </div>
  )
}

function CallCard({ message }: { message: MessageResponse }) {
  const tool = message.tool_content
  return (
    <div className="tool">
      <div className="th">
        <span className="kind">TOOL_CALL</span>
        <span className="tname">{tool?.tool_name ?? message.content}</span>
      </div>
      {tool !== undefined && (
        <pre>
          <span className="k">input </span>
          {formatJson(tool.input)}
        </pre>
      )}
    </div>
  )
}

function ResultCard({ message }: { message: MessageResponse }) {
  const tool = message.tool_content
  const errored = tool?.is_error === true
  return (
    <div className={errored ? 'tool err' : 'tool'}>
      <div className="th">
        <span className="kind">TOOL_RESULT</span>
        {tool !== undefined && <span className="tname">{tool.tool_name}</span>}
        {/* Slate chip, never red, and only when it is actually true — an
            "is_error: false" on every result is noise, not information. */}
        {errored && <span className="chip bad">is_error</span>}
      </div>
      <pre>
        <span className="k">output </span>
        {/* `content` already flattens the result output (with "[empty
            result]" for blank), so it is the payload either way. */}
        <span className="s">{message.content}</span>
      </pre>
    </div>
  )
}

/** `{}` when empty (the mockup's literal), pretty-printed otherwise. */
function formatJson(input: Record<string, unknown> | undefined): string {
  if (input === undefined || Object.keys(input).length === 0) return '{}'
  return JSON.stringify(input, null, 2)
}
