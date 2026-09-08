import { useCallback, useEffect, useRef, useState } from 'react'
import type { ApiClient } from '../../api/client'
import { ApiError, NetworkError } from '../../api/errors'
import type {
  ConversationResponse,
  InvocationResponse,
  InvocationStatus,
  MessageResponse,
} from '../../api/types'
import { buildThreadItems, sortMessages, windowItems } from './thread-model'
import type { LedgerRecorder } from '../../session/LedgerContext'
import type { InvocationRecord, ThreadItem } from './thread-model'

/**
 * The send/walk/poll/settle machine behind the Conversation view.
 *
 * Phases: idle → posting → walking → polling → settling → idle, plus
 * `stopped` (polling gave up — cap or repeated failures — Resume restarts
 * it). One invocation in flight at a time; the machine is started ONLY
 * from event handlers (send/resume), so StrictMode's double-mount cannot
 * start it twice; the single effect is the unmount cleanup. All mutable
 * machinery lives in refs, and every await re-checks the generation
 * counter before applying results.
 *
 * Polling is honest: a setTimeout chain (never overlapping ticks) every
 * 2s, each tick = invocation status + incremental message fetch from the
 * tail cursor, so the tool-loop trace grows live — no fake streaming.
 */

const POLL_INTERVAL_MS = 2_000
const POLL_CAP_MS = 5 * 60_000
const MAX_CONSECUTIVE_POLL_FAILURES = 5
const WALK_PAGE_LIMIT = 200
const RENDER_WINDOW = 50
const SETTLE_GRACE_ATTEMPTS = 2
const SETTLE_GRACE_DELAY_MS = 1_000

export type MachinePhase = 'idle' | 'posting' | 'walking' | 'polling' | 'settling' | 'stopped'

export interface SendFailure {
  message: string
  /** Backend field violations, verbatim (field names are camelCase). */
  fields: { field: string; message: string }[]
}

export interface ConversationMachineState {
  phase: MachinePhase
  conversation: ConversationResponse | null
  visibleItems: ThreadItem[]
  hiddenMessageCount: number
  live: { invocationId: string; status: InvocationStatus } | null
  stopReason: 'cap' | 'poll-failures' | null
  /** Non-recoverable poll stop (404/401) — quiet note, thread kept. */
  fatalNote: string | null
  sendError: SendFailure | null
  wireLeft: string | null
  wireRight: string | null
  /**
   * Id of the earliest message THIS session sent into the bound
   * conversation, or null when it has sent none. Purely derived (the
   * smallest recorded trigger id); the thread uses it to mark where
   * invocation meta starts existing, which is otherwise inferable only by
   * noticing that older replies have no meta line.
   */
  sessionAnchorMessageId: string | null
}

interface ConvoCacheEntry {
  conversation: ConversationResponse
  messages: Map<string, MessageResponse>
  /** Id of the last message held — the incremental fetch cursor. */
  tailCursor: string | null
  invocations: InvocationRecord[]
  revealSteps: number
}

interface LiveInvocation {
  invocationId: string
  triggerMessageId: string
  status: InvocationStatus
  capDeadline: number
  failures: number
}

interface Machine {
  gen: number
  timer: number | null
  abort: AbortController | null
  /** Per-conversation buffers for the life of the view (A→B→A keeps meta). */
  cache: Map<string, ConvoCacheEntry>
  currentConvId: string | null
  live: LiveInvocation | null
  lastSubmission: {
    agentId: string
    identityRef: string
    content: string
    key: string
    accepted: boolean
  } | null
}

const INITIAL_STATE: ConversationMachineState = {
  phase: 'idle',
  conversation: null,
  visibleItems: [],
  hiddenMessageCount: 0,
  live: null,
  stopReason: null,
  fatalNote: null,
  sendError: null,
  wireLeft: null,
  wireRight: null,
  sessionAnchorMessageId: null,
}

/**
 * @param ledger Optional observer of what this machine sees (the session
 *   ledger). It is only ever written to, at the points the machine already
 *   passes through — it never steers polling, binding or sending.
 */
export function useConversationMachine(client: ApiClient, ledger: LedgerRecorder | null = null) {
  const [state, setState] = useState<ConversationMachineState>(INITIAL_STATE)
  // Read through a ref so the emissions never enter a callback's deps.
  const ledgerRef = useRef(ledger)
  ledgerRef.current = ledger
  const machineRef = useRef<Machine>({
    gen: 0,
    timer: null,
    abort: null,
    cache: new Map(),
    currentConvId: null,
    live: null,
    lastSubmission: null,
  })

  useEffect(() => {
    const machine = machineRef.current
    return () => {
      // Unmount is the single teardown (RequireSession unmounts the view on
      // disconnect too): kill in-flight fetches and the timer chain.
      // An invocation still being watched loses its watcher here — say so,
      // rather than leaving the ledger believing it is still being polled.
      if (machine.live !== null) {
        ledgerRef.current?.watchEnded(machine.live.invocationId, 'navigated', Date.now())
      }
      machine.gen += 1
      machine.abort?.abort()
      if (machine.timer !== null) window.clearTimeout(machine.timer)
    }
  }, [])

  const patch = useCallback((partial: Partial<ConversationMachineState>) => {
    setState((prev) => ({ ...prev, ...partial }))
  }, [])

  /** Rebuild render items from the current buffer and publish them. */
  const sync = useCallback(() => {
    const machine = machineRef.current
    const entry = machine.currentConvId !== null ? machine.cache.get(machine.currentConvId) : undefined
    if (entry === undefined) {
      patch({
        conversation: null,
        visibleItems: [],
        hiddenMessageCount: 0,
        sessionAnchorMessageId: null,
      })
      return
    }
    const items = buildThreadItems(sortMessages(entry.messages.values()), entry.invocations)
    const { visible, hiddenMessageCount } = windowItems(
      items,
      RENDER_WINDOW * (1 + entry.revealSteps),
    )
    let anchor: string | null = null
    for (const record of entry.invocations) {
      if (anchor === null || record.triggerMessageId < anchor) anchor = record.triggerMessageId
    }
    patch({
      conversation: entry.conversation,
      visibleItems: visible,
      hiddenMessageCount,
      sessionAnchorMessageId: anchor,
    })
  }, [patch])

  /**
   * Fetch forward from the entry's tail cursor until next_cursor is null,
   * appending into the buffer (Map ⇒ duplicates are idempotent) and
   * advancing the tail as it goes — so an interrupted walk resumes from
   * where it stopped. Forward-only keyset: this is also the initial full
   * history walk (the API cannot page backwards).
   */
  const fetchForward = useCallback(
    async (entry: ConvoCacheEntry, signal: AbortSignal, showProgress: boolean) => {
      let cursor = entry.tailCursor
      let page = 0
      for (;;) {
        page += 1
        if (showProgress) {
          patch({ wireRight: `GET /v1/conversations/{id}/messages · page ${page}` })
        }
        const result = await client.listMessages(
          entry.conversation.id,
          cursor !== null ? { limit: WALK_PAGE_LIMIT, cursor } : { limit: WALK_PAGE_LIMIT },
          signal,
        )
        for (const message of result.data) entry.messages.set(message.id, message)
        const last = result.data[result.data.length - 1]
        if (last !== undefined) entry.tailCursor = last.id
        if (result.next_cursor === null) return
        cursor = result.next_cursor
      }
    },
    [client, patch],
  )

  const scheduleTick = useCallback(
    (delayMs: number) => {
      const machine = machineRef.current
      const gen = machine.gen
      machine.timer = window.setTimeout(() => {
        void tickRef.current(gen)
      }, delayMs)
    },
    [],
  )

  const settle = useCallback(
    async (invocation: InvocationResponse, gen: number) => {
      const machine = machineRef.current
      patch({ phase: 'settling' })
      ledgerRef.current?.terminal(invocation.id, invocation, Date.now())
      const entry =
        machine.currentConvId !== null ? machine.cache.get(machine.currentConvId) : undefined

      if (entry !== undefined && machine.live !== null) {
        const trigger = machine.live.triggerMessageId
        const hasReply = () =>
          [...entry.messages.values()].some(
            (m) => m.id > trigger && (m.role === 'AGENT' || m.role === 'SYSTEM'),
          )
        // Status and messages are separate reads: COMPLETED can be visible
        // a beat before the final AGENT append. A couple of grace refetches
        // close the gap; if nothing shows up, the standalone meta row is
        // the honest fallback.
        for (
          let attempt = 0;
          attempt < SETTLE_GRACE_ATTEMPTS && invocation.status === 'COMPLETED' && !hasReply();
          attempt += 1
        ) {
          await new Promise((resolve) => {
            machine.timer = window.setTimeout(resolve, SETTLE_GRACE_DELAY_MS)
          })
          if (gen !== machine.gen) return
          const controller = new AbortController()
          machine.abort = controller
          try {
            await fetchForward(entry, controller.signal, false)
          } catch {
            break
          }
          if (gen !== machine.gen) return
        }
        let record = entry.invocations.find((r) => r.invocationId === invocation.id)
        if (record === undefined) {
          // The attach that should have stored it failed transiently; the
          // invocation response itself carries the trigger link.
          record = {
            invocationId: invocation.id,
            triggerMessageId: invocation.trigger_message_id,
            outcome: null,
          }
          entry.invocations.push(record)
        }
        record.outcome = {
          status: invocation.status,
          failureReason: invocation.failure_reason,
          durationMs: invocationDurationMs(invocation),
        }
      }

      machine.live = null
      patch({
        phase: 'idle',
        live: null,
        wireRight: `GET /v1/invocations/${shortId(invocation.id)} → ${invocation.status}`,
      })
      sync()
    },
    [fetchForward, patch, sync],
  )

  const tick = useCallback(
    async (gen: number) => {
      const machine = machineRef.current
      if (gen !== machine.gen || machine.live === null) return
      if (Date.now() > machine.live.capDeadline) {
        ledgerRef.current?.watchEnded(machine.live.invocationId, 'cap', Date.now())
        patch({ phase: 'stopped', stopReason: 'cap' })
        return
      }
      const controller = new AbortController()
      machine.abort = controller
      try {
        const invocation = await client.getInvocation(machine.live.invocationId, controller.signal)
        if (gen !== machine.gen || machine.live === null) return
        machine.live.status = invocation.status
        machine.live.failures = 0
        ledgerRef.current?.polled(invocation.id, invocation.status, Date.now())
        let entry =
          machine.currentConvId !== null ? machine.cache.get(machine.currentConvId) : undefined
        if (entry === undefined && machine.currentConvId !== null) {
          // The initial attach failed transiently after the 202 — rebuild
          // the entry here so the thread still materializes.
          const conversation = await client.getConversation(machine.currentConvId, controller.signal)
          if (gen !== machine.gen || machine.live === null) return
          entry = {
            conversation,
            messages: new Map(),
            tailCursor: null,
            invocations: [
              {
                invocationId: machine.live.invocationId,
                triggerMessageId: machine.live.triggerMessageId,
                outcome: null,
              },
            ],
            revealSteps: 0,
          }
          machine.cache.set(machine.currentConvId, entry)
        }
        if (entry !== undefined) {
          await fetchForward(entry, controller.signal, false)
          if (gen !== machine.gen || machine.live === null) return
        }
        patch({
          live: { invocationId: invocation.id, status: invocation.status },
          wireRight: `GET /v1/invocations/${shortId(invocation.id)} → ${invocation.status}`,
        })
        sync()
        if (invocation.status === 'COMPLETED' || invocation.status === 'FAILED') {
          await settle(invocation, gen)
          return
        }
        scheduleTick(POLL_INTERVAL_MS)
      } catch (cause) {
        if (gen !== machine.gen || machine.live === null) return
        if (isFatalPollError(cause)) {
          ledgerRef.current?.watchEnded(machine.live.invocationId, 'fatal', Date.now())
          machine.live = null
          patch({
            phase: 'idle',
            live: null,
            fatalNote: `Polling stopped: ${describeError(cause)}`,
            wireRight: null,
          })
          return
        }
        machine.live.failures += 1
        patch({ wireRight: `poll failed (${machine.live.failures}) · retrying` })
        if (machine.live.failures >= MAX_CONSECUTIVE_POLL_FAILURES) {
          ledgerRef.current?.watchEnded(machine.live.invocationId, 'poll-failures', Date.now())
          patch({ phase: 'stopped', stopReason: 'poll-failures' })
          return
        }
        scheduleTick(POLL_INTERVAL_MS)
      }
    },
    [client, fetchForward, patch, scheduleTick, settle, sync],
  )
  // scheduleTick fires before `tick` is assigned in source order — route
  // through a ref updated every render.
  const tickRef = useRef(tick)
  tickRef.current = tick

  /**
   * Attach to the 202's conversation: new id → fetch the doc + full
   * forward history walk; cached id → refresh doc + catch-up from the
   * stored tail (this is also the tick-0 that makes the just-sent USER
   * bubble appear immediately).
   */
  const attach = useCallback(
    async (conversationId: string, record: InvocationRecord | null, gen: number) => {
      const machine = machineRef.current
      machine.currentConvId = conversationId
      const controller = new AbortController()
      machine.abort = controller
      let entry = machine.cache.get(conversationId)
      if (entry === undefined) {
        patch({ phase: 'walking' })
        const conversation = await client.getConversation(conversationId, controller.signal)
        if (gen !== machine.gen) return
        entry = {
          conversation,
          messages: new Map(),
          tailCursor: null,
          invocations: [],
          revealSteps: 0,
        }
        machine.cache.set(conversationId, entry)
        if (record !== null) entry.invocations.push(record)
        await fetchForward(entry, controller.signal, true)
      } else {
        if (record !== null) entry.invocations.push(record)
        await fetchForward(entry, controller.signal, false)
      }
      if (gen !== machine.gen) return
      sync()
      if (record !== null) ledgerRef.current?.bound(record.invocationId, Date.now())
    },
    [client, fetchForward, patch, sync],
  )

  const send = useCallback(
    async (
      agentId: string,
      identityRef: string,
      content: string,
      /** The agent's name as listed when sent — for the ledger's row only. */
      agentName: string | null = null,
    ): Promise<boolean> => {
      const machine = machineRef.current
      const trimmed = content.trim()
      if (machine.live !== null || trimmed === '') return false

      // Idempotency-Key per submission: retrying the same failed submission
      // reuses the key (the backend replays the original 202 instead of
      // duplicating); changing anything mints a new one.
      const last = machine.lastSubmission
      const key =
        last !== null &&
        !last.accepted &&
        last.agentId === agentId &&
        last.identityRef === identityRef &&
        last.content === trimmed
          ? last.key
          : crypto.randomUUID()
      machine.lastSubmission = { agentId, identityRef, content: trimmed, key, accepted: false }

      const gen = machine.gen
      patch({
        phase: 'posting',
        sendError: null,
        fatalNote: null,
        stopReason: null,
        wireLeft: 'POST /v1/agents/{id}/messages → …',
      })
      try {
        const accepted = await client.sendMessage(
          agentId,
          { external_identity_ref: identityRef, content: trimmed },
          key,
        )
        if (gen !== machine.gen) return false
        machine.lastSubmission.accepted = true
        machine.live = {
          invocationId: accepted.invocation_id,
          triggerMessageId: accepted.message_id,
          status: 'PENDING',
          capDeadline: Date.now() + POLL_CAP_MS,
          failures: 0,
        }
        patch({
          wireLeft: 'POST /v1/agents/{id}/messages → 202',
          live: { invocationId: accepted.invocation_id, status: 'PENDING' },
        })
        ledgerRef.current?.sent({
          invocationId: accepted.invocation_id,
          conversationId: accepted.conversation_id,
          triggerMessageId: accepted.message_id,
          agentId,
          agentName,
          idempotencyKey: key,
          at: Date.now(),
        })
        const record: InvocationRecord = {
          invocationId: accepted.invocation_id,
          triggerMessageId: accepted.message_id,
          outcome: null,
        }
        try {
          await attach(accepted.conversation_id, record, gen)
        } catch {
          // The walk/catch-up failing is not fatal: polling ticks resume
          // the fetch from the stored tail (the buffer dedupes).
        }
        if (gen !== machine.gen) return false
        patch({ phase: 'polling' })
        scheduleTick(POLL_INTERVAL_MS)
        return true
      } catch (cause) {
        if (gen !== machine.gen) return false
        patch({
          phase: 'idle',
          sendError: toSendFailure(cause),
          wireLeft: `POST /v1/agents/{id}/messages → ${cause instanceof ApiError ? cause.status : 'network error'}`,
        })
        return false
      }
    },
    [attach, client, patch, scheduleTick],
  )

  /** Restart polling after a stop, with a fresh cap and failure budget. */
  const resume = useCallback(() => {
    const machine = machineRef.current
    if (machine.live === null) return
    machine.live.capDeadline = Date.now() + POLL_CAP_MS
    machine.live.failures = 0
    ledgerRef.current?.resumed(machine.live.invocationId, Date.now())
    patch({ phase: 'polling', stopReason: null })
    scheduleTick(0)
  }, [patch, scheduleTick])

  /** Reveal one more window of already-walked history from the buffer. */
  const loadEarlier = useCallback(() => {
    const machine = machineRef.current
    const entry =
      machine.currentConvId !== null ? machine.cache.get(machine.currentConvId) : undefined
    if (entry === undefined) return
    entry.revealSteps += 1
    sync()
  }, [sync])

  /**
   * Bind an existing conversation WITHOUT sending — the way in from the
   * ledger ("Open in conversation"). Same walk as a send's attach, minus
   * the invocation record. Refused while something is being watched: a
   * live poll is not hijacked by navigation.
   */
  const open = useCallback(
    async (conversationId: string) => {
      const machine = machineRef.current
      if (machine.live !== null) return
      machine.gen += 1
      const gen = machine.gen
      machine.abort?.abort()
      patch({ sendError: null, fatalNote: null, stopReason: null })
      try {
        await attach(conversationId, null, gen)
        if (gen !== machine.gen) return
        patch({ phase: 'idle' })
      } catch (cause) {
        if (gen !== machine.gen) return
        patch({ phase: 'idle', fatalNote: `Could not open the conversation: ${describeError(cause)}` })
      }
    },
    [attach, patch],
  )

  return { state, send, resume, loadEarlier, open }
}

export function shortId(id: string): string {
  return `${id.slice(0, 8)}…`
}

/**
 * How long the invocation took, from the two timestamps the wire already
 * carries. Null when `completed_at` is absent or either stamp is unparsable
 * — a duration is never guessed.
 */
function invocationDurationMs(invocation: InvocationResponse): number | null {
  if (invocation.completed_at === null) return null
  const start = new Date(invocation.created_at).getTime()
  const end = new Date(invocation.completed_at).getTime()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return null
  return end - start
}

function isFatalPollError(cause: unknown): boolean {
  if (cause instanceof ApiError) {
    if (cause.status === 404 || cause.status === 401) return true
    if (cause.code === 'invalid_cursor') return true
    // 4xx other than the transient-looking proxy 502s: no retry will fix it.
    return cause.status >= 400 && cause.status < 500
  }
  // NetworkError and 5xx/proxy errors are transient.
  return false
}

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) return `${cause.status} ${cause.code}`
  if (cause instanceof NetworkError) return 'network error'
  return cause instanceof Error ? cause.message : String(cause)
}

function toSendFailure(cause: unknown): SendFailure {
  if (cause instanceof ApiError) {
    return {
      message: `${cause.status} ${cause.code} — ${cause.message}`,
      fields: cause.envelope.errors ?? [],
    }
  }
  if (cause instanceof NetworkError) {
    return {
      message:
        'The request may not have reached the instance. Sending the same text again is safe — ' +
        'it reuses the same Idempotency-Key, so the backend will not ingest a duplicate.',
      fields: [],
    }
  }
  return { message: cause instanceof Error ? cause.message : String(cause), fields: [] }
}
