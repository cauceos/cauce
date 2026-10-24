import type { MessagePart, MessageResponse, MessageRole } from '../api/types'

/** A wire message as the instance sends it: `content` derived from `parts`. */
export function message(
  role: MessageRole,
  content: string,
  parts: MessagePart[] = [{ type: 'text', text: content }],
): MessageResponse {
  return {
    id: crypto.randomUUID(),
    role,
    content,
    parts,
    created_at: '2026-10-24T10:15:00Z',
  }
}
