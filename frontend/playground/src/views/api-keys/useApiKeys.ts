import { useCallback, useEffect, useRef, useState } from 'react'
import type { ApiClient } from '../../api/client'
import { ApiError, NetworkError } from '../../api/errors'
import type { ApiKeyResponse } from '../../api/types'

export interface ApiKeysState {
  status: 'idle' | 'loading' | 'loaded' | 'error'
  /** The tenant the list was loaded for; empty before the first load. */
  tenantId: string
  keys: ApiKeyResponse[]
  error: string | null
}

/**
 * The key metadata of one tenant: GET /v1/tenants/{id}/api-keys, a bare
 * array. Issue and revoke are done by the view against the client; both
 * then call `refresh()` rather than patching the list — `revoked_at` is the
 * server's to set, and the list is unpaginated, so a refetch can never lose
 * the row the way a paged list could.
 */
export function useApiKeys(client: ApiClient) {
  const [state, setState] = useState<ApiKeysState>({ status: 'idle', tenantId: '', keys: [], error: null })
  const genRef = useRef(0)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => () => abortRef.current?.abort(), [])

  const load = useCallback(
    async (tenantId: string, quiet = false) => {
      genRef.current += 1
      const gen = genRef.current
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller
      // A quiet reload (after issue/revoke) keeps the rows on screen instead
      // of flashing a skeleton for a list that is about to look the same.
      setState((prev) =>
        quiet && prev.tenantId === tenantId
          ? { ...prev, status: 'loading', error: null }
          : { status: 'loading', tenantId, keys: [], error: null },
      )
      try {
        const keys = await client.listApiKeys(tenantId, controller.signal)
        if (gen !== genRef.current) return
        setState({ status: 'loaded', tenantId, keys, error: null })
      } catch (cause) {
        if (gen !== genRef.current) return
        setState({ status: 'error', tenantId, keys: [], error: describeError(cause) })
      }
    },
    [client],
  )

  const refresh = useCallback(() => {
    if (state.tenantId !== '') void load(state.tenantId, true)
  }, [load, state.tenantId])

  return { ...state, load, refresh }
}

/** User-facing text for a failed list. A 404 owns its ambiguity, as in Tenants. */
export function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 404) return 'no tenant visible for this id (it may not exist, or not be yours)'
    if (cause.code === 'invalid_path_parameter' || cause.status === 400) return 'not a valid tenant id'
    return `${cause.status} ${cause.code} — ${cause.message}`
  }
  if (cause instanceof NetworkError) return 'request failed — is the instance still reachable?'
  return cause instanceof Error ? cause.message : String(cause)
}
