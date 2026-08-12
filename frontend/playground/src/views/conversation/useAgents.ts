import { useCallback, useEffect, useRef, useState } from 'react'
import type { ApiClient } from '../../api/client'
import { ApiError, NetworkError } from '../../api/errors'
import type { AgentResponse } from '../../api/types'

export interface AgentsState {
  status: 'idle' | 'loading' | 'loaded' | 'error'
  agents: AgentResponse[]
  error: string | null
}

/**
 * Agents of a pasted tenant id: walks GET /v1/tenants/{id}/agents by
 * next_cursor until null. An out-of-scope tenant answers 200 with an
 * empty list (not 404), so "wrong id" and "empty tenant" are deliberately
 * indistinguishable — the UI copy says so.
 */
export function useAgents(client: ApiClient) {
  const [state, setState] = useState<AgentsState>({ status: 'idle', agents: [], error: null })
  const genRef = useRef(0)
  const abortRef = useRef<AbortController | null>(null)

  const load = useCallback(
    async (tenantId: string) => {
      genRef.current += 1
      const gen = genRef.current
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller
      setState({ status: 'loading', agents: [], error: null })
      try {
        const agents: AgentResponse[] = []
        let cursor: string | undefined
        do {
          const page = await client.listAgents(
            tenantId,
            cursor !== undefined ? { limit: 200, cursor } : { limit: 200 },
            controller.signal,
          )
          agents.push(...page.data)
          cursor = page.next_cursor ?? undefined
        } while (cursor !== undefined)
        if (gen !== genRef.current) return
        setState({ status: 'loaded', agents, error: null })
      } catch (cause) {
        if (gen !== genRef.current) return
        setState({ status: 'error', agents: [], error: describeLoadError(cause) })
      }
    },
    [client],
  )

  useEffect(
    () => () => {
      genRef.current += 1
      abortRef.current?.abort()
    },
    [],
  )

  return { ...state, load }
}

function describeLoadError(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.code === 'invalid_path_parameter' || cause.status === 400) {
      return 'not a valid tenant id'
    }
    return `${cause.status} ${cause.code} — ${cause.message}`
  }
  if (cause instanceof NetworkError) return 'request failed — is the instance still reachable?'
  return cause instanceof Error ? cause.message : String(cause)
}
