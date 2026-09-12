import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import type {
  FailureReason,
  InvocationResponse,
  InvocationStatus,
  InvocationUsageResponse,
} from '../api/types'
import { useSession } from './SessionContext'

/**
 * The session ledger: every invocation this browser started, with what the
 * client itself observed — send time, poll ticks, first status seen,
 * terminal status, wall-clock spans. Plus any invocation looked up by id,
 * which arrives with server facts only.
 *
 * There is no invocation listing in the API, so this is the only place
 * those facts exist. It is memory-only by design: a reload empties it, and
 * a disconnect clears it. It lives at app level because the Conversation
 * machine that produces the observations unmounts on navigation.
 *
 * The machine EMITS into it through `LedgerRecorder` and never reads it —
 * an observer, the same shape as the backend's audit recorder.
 */

/** Why this browser stopped watching an invocation that had not finished. */
export type WatchEnd = 'cap' | 'poll-failures' | 'fatal' | 'navigated'

export interface LedgerEntry {
  invocationId: string
  /** Sent by this browser (and observed), or only looked up by id. */
  origin: 'observed' | 'lookup'
  conversationId: string | null
  triggerMessageId: string | null
  /** The agent this send targeted — the client's own choice, by id. */
  agentId: string | null
  /** Its name as listed when sent; identity, not a property that can drift. */
  agentName: string | null
  idempotencyKey: string | null

  /* Client wall clock, ms since epoch. Never server metrics. */
  sentAt: number | null
  boundAt: number | null
  /** First status other than PENDING seen — where "waiting" ends. */
  firstActiveAt: number | null
  terminalAt: number | null
  watchEndedAt: number | null
  watchEnd: WatchEnd | null
  polls: number

  /* Wire facts. */
  lastSeenStatus: InvocationStatus | null
  failureReason: FailureReason | null
  serverCreatedAt: string | null
  serverCompletedAt: string | null
  /** The terminal status came from a lookup, not from watching. */
  resolvedByLookup: boolean
  /**
   * Token usage as the wire delivered it. Three states, kept apart on
   * purpose: `undefined` — no response carrying the field has landed yet
   * (in flight, or an instance without it); `null` — the server said
   * nothing was recorded, which is not zero; an object — the facts.
   */
  usage: InvocationUsageResponse | null | undefined
}

/** What a row IS, derived: PENDING while watched, UNKNOWN once the watch ended without a terminal. */
export type LedgerStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'UNKNOWN'

export function ledgerStatus(entry: LedgerEntry): LedgerStatus {
  if (entry.lastSeenStatus === 'COMPLETED' || entry.lastSeenStatus === 'FAILED') return entry.lastSeenStatus
  if (entry.watchEnd !== null) return 'UNKNOWN'
  if (entry.origin === 'lookup') return 'UNKNOWN'
  return 'PENDING'
}

/** The write side, handed to the Conversation machine. Stable for the app's life. */
export interface LedgerRecorder {
  sent(event: {
    invocationId: string
    conversationId: string
    triggerMessageId: string
    agentId: string
    agentName: string | null
    idempotencyKey: string
    at: number
  }): void
  bound(invocationId: string, at: number): void
  polled(invocationId: string, status: InvocationStatus, at: number): void
  terminal(invocationId: string, invocation: InvocationResponse, at: number): void
  watchEnded(invocationId: string, reason: WatchEnd, at: number): void
  resumed(invocationId: string, at: number): void
}

interface LedgerContextValue {
  entries: LedgerEntry[]
  recorder: LedgerRecorder
  /** Merge server facts for any id — a refresh of a known row, or a new lookup-only row. */
  lookup(invocation: InvocationResponse): void
  clear(): void
}

const LedgerContext = createContext<LedgerContextValue | null>(null)

/** Bounded so a long session cannot grow without limit; oldest go first. */
const MAX_ENTRIES = 200

const isTerminal = (status: InvocationStatus) => status === 'COMPLETED' || status === 'FAILED'

export function LedgerProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<LedgerEntry[]>([])
  const { status } = useSession()

  // A session that ends takes its ledger with it — nothing here should
  // outlive the key that produced it.
  useEffect(() => {
    if (status === 'disconnected') setEntries([])
  }, [status])

  // The write side closes over setEntries alone, so it is built once and
  // stays referentially stable for the app's life — the machine can hold it
  // without re-wiring its callbacks on every observation.
  const actions = useMemo(() => {
    const update = (invocationId: string, fn: (entry: LedgerEntry) => LedgerEntry) =>
      setEntries((prev) => prev.map((e) => (e.invocationId === invocationId ? fn(e) : e)))

    const recorder: LedgerRecorder = {
      sent(event) {
        setEntries((prev) => {
          const entry: LedgerEntry = {
            invocationId: event.invocationId,
            origin: 'observed',
            conversationId: event.conversationId,
            triggerMessageId: event.triggerMessageId,
            agentId: event.agentId,
            agentName: event.agentName,
            idempotencyKey: event.idempotencyKey,
            sentAt: event.at,
            boundAt: null,
            firstActiveAt: null,
            terminalAt: null,
            watchEndedAt: null,
            watchEnd: null,
            polls: 0,
            lastSeenStatus: 'PENDING',
            failureReason: null,
            serverCreatedAt: null,
            serverCompletedAt: null,
            resolvedByLookup: false,
            usage: undefined,
          }
          // Newest first; a re-sent id (idempotent replay) replaces its row.
          const rest = prev.filter((e) => e.invocationId !== event.invocationId)
          return [entry, ...rest].slice(0, MAX_ENTRIES)
        })
      },
      bound(invocationId, at) {
        update(invocationId, (e) => (e.boundAt === null ? { ...e, boundAt: at } : e))
      },
      polled(invocationId, status, at) {
        update(invocationId, (e) => ({
          ...e,
          polls: e.polls + 1,
          lastSeenStatus: status,
          firstActiveAt: e.firstActiveAt ?? (status !== 'PENDING' ? at : null),
          terminalAt: e.terminalAt ?? (isTerminal(status) ? at : null),
        }))
      },
      terminal(invocationId, invocation, at) {
        update(invocationId, (e) => ({
          ...e,
          lastSeenStatus: invocation.status,
          failureReason: invocation.failure_reason,
          serverCreatedAt: invocation.created_at,
          serverCompletedAt: invocation.completed_at,
          usage: invocation.usage,
          firstActiveAt: e.firstActiveAt ?? at,
          terminalAt: e.terminalAt ?? at,
          watchEnd: null,
        }))
      },
      watchEnded(invocationId, reason, at) {
        update(invocationId, (e) =>
          e.terminalAt !== null ? e : { ...e, watchEnd: reason, watchEndedAt: at },
        )
      },
      resumed(invocationId) {
        update(invocationId, (e) => ({ ...e, watchEnd: null, watchEndedAt: null }))
      },
    }

    const lookup = (invocation: InvocationResponse) => {
      setEntries((prev) => {
        const existing = prev.find((e) => e.invocationId === invocation.id)
        if (existing !== undefined) {
          // A refresh: server facts land; a terminal resolves an UNKNOWN, and
          // says it came from a lookup rather than from watching.
          const gainedTerminal = existing.terminalAt === null && isTerminal(invocation.status)
          return prev.map((e) =>
            e.invocationId !== invocation.id
              ? e
              : {
                  ...e,
                  lastSeenStatus: invocation.status,
                  failureReason: invocation.failure_reason,
                  serverCreatedAt: invocation.created_at,
                  serverCompletedAt: invocation.completed_at,
                  usage: invocation.usage,
                  resolvedByLookup: e.resolvedByLookup || gainedTerminal,
                },
          )
        }
        const entry: LedgerEntry = {
          invocationId: invocation.id,
          origin: 'lookup',
          conversationId: invocation.conversation_id,
          triggerMessageId: invocation.trigger_message_id,
          agentId: null,
          agentName: null,
          idempotencyKey: null,
          sentAt: null,
          boundAt: null,
          firstActiveAt: null,
          terminalAt: null,
          watchEndedAt: null,
          watchEnd: null,
          polls: 0,
          lastSeenStatus: invocation.status,
          failureReason: invocation.failure_reason,
          serverCreatedAt: invocation.created_at,
          serverCompletedAt: invocation.completed_at,
          resolvedByLookup: isTerminal(invocation.status),
          usage: invocation.usage,
        }
        return [entry, ...prev].slice(0, MAX_ENTRIES)
      })
    }

    return { recorder, lookup, clear: () => setEntries([]) }
  }, [])

  const value = useMemo<LedgerContextValue>(() => ({ entries, ...actions }), [entries, actions])

  return <LedgerContext.Provider value={value}>{children}</LedgerContext.Provider>
}

export function useLedger(): LedgerContextValue {
  const value = useContext(LedgerContext)
  if (value === null) throw new Error('useLedger must be used inside <LedgerProvider>')
  return value
}
