import { useCallback, useEffect, useRef, useState } from 'react'
import type { ApiClient } from '../../api/client'
import { ApiError, NetworkError } from '../../api/errors'
import type { AgentResponse } from '../../api/types'

const PAGE_LIMIT = 50

/**
 * Agents of a CLIENT tenant, loaded page-by-page with an explicit
 * "Load more" (the mockup's contract) — distinct from Conversation's
 * `useAgents`, which walks every page at once to fill a selector. An
 * out-of-scope tenant answers 200 with an empty list (not 404), so a wrong
 * id and an empty tenant look identical; the UI copy says so.
 */
export interface AgentListState {
  status: 'idle' | 'loading' | 'loaded' | 'error'
  agents: AgentResponse[]
  nextCursor: string | null
  loadingMore: boolean
  error: string | null
}

export function useAgentList(client: ApiClient) {
  const [state, setState] = useState<AgentListState>({
    status: 'idle',
    agents: [],
    nextCursor: null,
    loadingMore: false,
    error: null,
  })
  const genRef = useRef(0)
  const abortRef = useRef<AbortController | null>(null)
  const tenantRef = useRef('')

  useEffect(
    () => () => {
      genRef.current += 1
      abortRef.current?.abort()
    },
    [],
  )

  const load = useCallback(
    async (rawTenantId: string) => {
      const tenantId = rawTenantId.trim()
      if (tenantId === '') return
      tenantRef.current = tenantId
      genRef.current += 1
      const gen = genRef.current
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller
      setState({ status: 'loading', agents: [], nextCursor: null, loadingMore: false, error: null })
      try {
        const page = await client.listAgents(tenantId, { limit: PAGE_LIMIT }, controller.signal)
        if (gen !== genRef.current) return
        setState({
          status: 'loaded',
          agents: page.data,
          nextCursor: page.next_cursor,
          loadingMore: false,
          error: null,
        })
      } catch (cause) {
        if (gen !== genRef.current) return
        setState({
          status: 'error',
          agents: [],
          nextCursor: null,
          loadingMore: false,
          error: describeError(cause),
        })
      }
    },
    [client],
  )

  const loadMore = useCallback(async () => {
    const cursor = state.nextCursor
    if (cursor === null || state.loadingMore) return
    const gen = genRef.current
    setState((prev) => ({ ...prev, loadingMore: true }))
    try {
      const page = await client.listAgents(tenantRef.current, { limit: PAGE_LIMIT, cursor })
      if (gen !== genRef.current) return
      setState((prev) => {
        const known = new Set(prev.agents.map((a) => a.id))
        const fresh = page.data.filter((a) => !known.has(a.id))
        return {
          ...prev,
          agents: [...prev.agents, ...fresh],
          nextCursor: page.next_cursor,
          loadingMore: false,
        }
      })
    } catch (cause) {
      if (gen !== genRef.current) return
      setState((prev) => ({ ...prev, loadingMore: false, error: describeError(cause) }))
    }
  }, [client, state.nextCursor, state.loadingMore])

  /** Fold a freshly created agent in at the top (deduped by id). */
  const add = useCallback((created: AgentResponse) => {
    setState((prev) => {
      if (prev.agents.some((a) => a.id === created.id)) return prev
      return { ...prev, status: 'loaded', agents: [created, ...prev.agents] }
    })
  }, [])

  return { ...state, load, loadMore, add }
}

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.code === 'invalid_path_parameter' || cause.status === 400) return 'not a valid tenant id'
    return `${cause.status} ${cause.code} — ${cause.message}`
  }
  if (cause instanceof NetworkError) return 'request failed — is the instance still reachable?'
  return cause instanceof Error ? cause.message : String(cause)
}
