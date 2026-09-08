import type { FailureReason, InvocationStatus, MessageResponse } from '../../api/types'

/**
 * Pure thread model — no React. Turns the raw message buffer plus the
 * invocations this view has witnessed into renderable items:
 *
 * · Attribution: the wire carries no message↔invocation link. The anchor is
 *   the 202's `message_id` (the trigger USER message): ids are UUIDv7 =
 *   thread order, and the view runs one invocation at a time, so every
 *   message BETWEEN invocation X's trigger and the next invocation's
 *   trigger belongs to X — regardless of which fetch delivered it. A
 *   concurrent writer to the same conversation (second tab, same identity)
 *   is indistinguishable on the wire; accepted playground heuristic.
 * · Grouping: each maximal run of consecutive TOOL_CALL/TOOL_RESULT
 *   messages renders as one collapsible loop-trace block. A SYSTEM message
 *   landing mid-loop (retryable provider failure) splits the run in two —
 *   honest: that is the wire order.
 * · Meta: one meta line per finished invocation, attached to its last
 *   attributed AGENT bubble; if the invocation left no AGENT bubble
 *   (FAILED after SYSTEM cards, or TIMEOUT/INTERNAL_ERROR which write no
 *   thread message at all), a standalone meta-only row instead. Historical
 *   messages get no meta — the wire does not carry it and none is invented.
 */

export interface InvocationRecord {
  invocationId: string
  /** `message_id` of the 202 — the trigger USER message. */
  triggerMessageId: string
  /* NOTE: the model is deliberately NOT recorded here. It is absent from
     the invocation wire, and deducing it from the agent a send targeted
     breaks in the case that matters most — switch agents mid-thread (two
     homonyms on different models is real on this instance) and the meta of
     the earlier messages would name the wrong one. A deduced field that
     lies at the edge is worse than a field that does not exist. */
  /** Terminal outcome; null while live (renders no meta yet). */
  outcome: InvocationOutcome | null
}

export interface InvocationOutcome {
  status: InvocationStatus
  failureReason: FailureReason | null
  /**
   * `completed_at - created_at` of the invocation, both on the wire. Null
   * when the row has no `completed_at` (it can be absent on a failure).
   */
  durationMs: number | null
}

export interface InvocationMetaInfo {
  invocationId: string
  status: InvocationStatus
  failureReason: FailureReason | null
  durationMs: number | null
}

export type ThreadItem =
  | { kind: 'user'; message: MessageResponse }
  | { kind: 'agent'; message: MessageResponse; meta: InvocationMetaInfo | null }
  | { kind: 'system'; message: MessageResponse }
  | { kind: 'loop'; key: string; toolCallCount: number; messages: MessageResponse[] }
  | { kind: 'meta-only'; key: string; meta: InvocationMetaInfo }

/** Ascending by id — UUIDv7 canonical text sorts identically to the DB. */
export function sortMessages(messages: Iterable<MessageResponse>): MessageResponse[] {
  return [...messages].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
}

export function buildThreadItems(
  sorted: MessageResponse[],
  invocations: InvocationRecord[],
): ThreadItem[] {
  const records = [...invocations].sort((a, b) =>
    a.triggerMessageId < b.triggerMessageId ? -1 : a.triggerMessageId > b.triggerMessageId ? 1 : 0,
  )

  const ownerOf = (messageId: string): InvocationRecord | null => {
    let owner: InvocationRecord | null = null
    for (const record of records) {
      if (messageId > record.triggerMessageId) owner = record
      else break
    }
    return owner
  }

  const items: ThreadItem[] = []
  // Where each invocation's meta should land: the item index of its last
  // attributed AGENT or SYSTEM message (AGENT → inline meta; SYSTEM or
  // none → standalone row).
  const lastAttributed = new Map<string, { itemIndex: number; role: 'AGENT' | 'SYSTEM' }>()
  const triggerItemIndex = new Map<string, number>()

  for (const message of sorted) {
    if (message.role === 'TOOL_CALL' || message.role === 'TOOL_RESULT') {
      const last = items[items.length - 1]
      if (last !== undefined && last.kind === 'loop') {
        last.messages.push(message)
        if (message.role === 'TOOL_CALL') last.toolCallCount += 1
      } else {
        items.push({
          kind: 'loop',
          key: message.id,
          toolCallCount: message.role === 'TOOL_CALL' ? 1 : 0,
          messages: [message],
        })
      }
      continue
    }

    if (message.role === 'USER') {
      items.push({ kind: 'user', message })
      triggerItemIndex.set(message.id, items.length - 1)
      continue
    }

    const owner = ownerOf(message.id)
    if (message.role === 'AGENT') {
      items.push({ kind: 'agent', message, meta: null })
    } else {
      items.push({ kind: 'system', message })
    }
    if (owner !== null) {
      lastAttributed.set(owner.invocationId, {
        itemIndex: items.length - 1,
        role: message.role === 'AGENT' ? 'AGENT' : 'SYSTEM',
      })
    }
  }

  // Meta placement for finished invocations, applied back-to-front so
  // standalone insertions do not shift pending indexes.
  const insertions: { index: number; item: ThreadItem }[] = []
  for (const record of records) {
    if (record.outcome === null) continue
    const meta: InvocationMetaInfo = {
      invocationId: record.invocationId,
      status: record.outcome.status,
      failureReason: record.outcome.failureReason,
      durationMs: record.outcome.durationMs,
    }
    const anchor = lastAttributed.get(record.invocationId)
    if (anchor !== undefined && anchor.role === 'AGENT') {
      const item = items[anchor.itemIndex]
      if (item.kind === 'agent') item.meta = meta
      continue
    }
    const afterIndex = anchor?.itemIndex ?? triggerItemIndex.get(record.triggerMessageId)
    insertions.push({
      index: (afterIndex ?? items.length - 1) + 1,
      item: { kind: 'meta-only', key: `meta-${record.invocationId}`, meta },
    })
  }
  insertions.sort((a, b) => b.index - a.index)
  for (const { index, item } of insertions) {
    items.splice(index, 0, item)
  }

  return items
}

/**
 * Render window over the tail: include whole items from the end until at
 * least `messageBudget` messages are visible (a loop block is never split).
 * `hiddenMessageCount > 0` ⇔ the "Load earlier messages" button shows.
 */
export function windowItems(
  items: ThreadItem[],
  messageBudget: number,
): { visible: ThreadItem[]; hiddenMessageCount: number } {
  const messagesIn = (item: ThreadItem): number => (item.kind === 'loop' ? item.messages.length : item.kind === 'meta-only' ? 0 : 1)

  let included = 0
  let start = items.length
  while (start > 0 && included < messageBudget) {
    start -= 1
    included += messagesIn(items[start])
  }
  let hidden = 0
  for (let i = 0; i < start; i += 1) hidden += messagesIn(items[i])
  return { visible: items.slice(start), hiddenMessageCount: hidden }
}
