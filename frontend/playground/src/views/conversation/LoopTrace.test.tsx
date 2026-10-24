import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { MessageResponse } from '../../api/types'
import { message } from '../../test/messages'
import { LoopTrace } from './LoopTrace'

function loop(...messages: MessageResponse[]) {
  return { kind: 'loop' as const, key: 'loop-1', toolCallCount: 1, messages }
}

describe('LoopTrace', () => {
  it('reads a tool round from its parts: the call with its input, the result with its output', () => {
    render(
      <LoopTrace
        item={loop(
          message('TOOL_CALL', 'search_catalog', [
            { type: 'tool_call', tool_call_id: 'c1', tool_name: 'search_catalog', input: { query: 'bravas' } },
          ]),
          message('TOOL_RESULT', '{"items":[]}', [
            { type: 'tool_result', tool_call_id: 'c1', tool_name: 'search_catalog', output: '{"items":[]}', is_error: false },
          ]),
        )}
      />,
    )

    expect(screen.getAllByText('search_catalog')).toHaveLength(2)
    expect(screen.getByText(/"query": "bravas"/)).toBeTruthy()
    expect(screen.getByText('{"items":[]}')).toBeTruthy()
    expect(screen.queryByText('is_error')).toBeNull()
  })

  it('marks a failed result, and only a failed one', () => {
    render(
      <LoopTrace
        item={loop(
          message('TOOL_RESULT', 'boom', [
            { type: 'tool_result', tool_call_id: 'c1', tool_name: 'check_availability', output: 'boom', is_error: true },
          ]),
        )}
      />,
    )

    expect(screen.getByText('is_error')).toBeTruthy()
  })

  it('falls back to tool_content for an instance from before message parts', () => {
    const legacy: MessageResponse = {
      id: crypto.randomUUID(),
      role: 'TOOL_CALL',
      content: 'get_current_time',
      tool_content: { tool_call_id: 'c1', tool_name: 'get_current_time', input: { tz: 'UTC' } },
      created_at: '2026-10-24T10:15:00Z',
    }

    render(<LoopTrace item={loop(legacy)} />)

    expect(screen.getByText(/"tz": "UTC"/)).toBeTruthy()
  })
})
