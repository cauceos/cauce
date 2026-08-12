import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { ApiClient } from '../api/client'
import { ApiError, NetworkError } from '../api/errors'

export type SessionStatus = 'disconnected' | 'connecting' | 'connected' | 'error'

export interface SessionError {
  kind: 'invalid-url' | 'unreachable' | 'unauthorized' | 'http' | 'unexpected'
  /** Actionable, user-facing text. */
  message: string
  /** Backend correlation id, when the failure produced an envelope that had one. */
  requestId?: string | null
}

export interface SessionContextValue {
  /** Normalized origin of the active session (or the default before any). */
  instanceUrl: string
  status: SessionStatus
  /** The last failed connect attempt, until the next attempt or success. */
  error: SessionError | null
  /**
   * Non-null exactly while status === 'connected'. The API key lives
   * privately inside this client, in React state — it is never persisted
   * to localStorage, sessionStorage, or cookies, so refreshing the page
   * forgets the session by design.
   */
  client: ApiClient | null
  /**
   * Workspace tenant id the user pasted (the API has no "who am I"
   * endpoint, so the caller's tenant cannot be discovered — see the
   * Deferred register). Shared session-wide so every area reuses it.
   * Memory-only, like everything here.
   */
  tenantId: string
  setTenantId(tenantId: string): void
  connect(instanceUrl: string, apiKey: string): Promise<boolean>
  disconnect(): void
}

const SessionContext = createContext<SessionContextValue | null>(null)

export const DEFAULT_INSTANCE_URL = 'http://localhost:8080'

export function SessionProvider({ children }: { children: ReactNode }) {
  const [instanceUrl, setInstanceUrl] = useState(DEFAULT_INSTANCE_URL)
  const [status, setStatus] = useState<SessionStatus>('disconnected')
  const [error, setError] = useState<SessionError | null>(null)
  const [client, setClient] = useState<ApiClient | null>(null)
  const [tenantId, setTenantId] = useState('')
  // Mirror of `client`, readable inside connect() without re-creating the
  // callback whenever the session changes.
  const clientRef = useRef<ApiClient | null>(null)

  const connect = useCallback(async (rawUrl: string, apiKey: string): Promise<boolean> => {
    // Attempts are transactional: a failed attempt surfaces its error but
    // never destroys a live session — the previous client is restored.
    const previous = clientRef.current
    const fail = (failure: SessionError): false => {
      if (previous !== null) {
        setClient(previous)
        setStatus('connected')
      } else {
        setClient(null)
        setStatus('error')
      }
      setError(failure)
      return false
    }

    const parsed = parseInstanceUrl(rawUrl)
    if (parsed.kind === 'invalid') {
      return fail({ kind: 'invalid-url', message: `"${rawUrl.trim()}" is not a valid http(s) URL.` })
    }
    if (parsed.kind === 'has-path') {
      return fail({
        kind: 'invalid-url',
        message:
          'Use the instance origin only (scheme://host:port) — a URL with a path prefix is not supported.',
      })
    }
    const origin = parsed.origin

    setStatus('connecting')
    setError(null)

    const candidate = new ApiClient(origin, apiKey)
    try {
      // Runtime-guard the health shape: the URL is free-form, so a 200 with
      // arbitrary JSON (a non-Cauce server) must not escape as a TypeError.
      const health: unknown = await candidate.health()
      const healthStatus =
        typeof health === 'object' &&
        health !== null &&
        typeof (health as { status?: unknown }).status === 'string'
          ? (health as { status: string }).status
          : null
      if (healthStatus === null) {
        return fail({
          kind: 'unexpected',
          message:
            'The server answered 200 but not with a health document — is this really a Cauce instance?',
        })
      }
      if (healthStatus !== 'UP') {
        return fail({
          kind: 'http',
          message: `The instance answered but reports health status "${healthStatus}".`,
        })
      }
      clientRef.current = candidate
      setClient(candidate)
      setInstanceUrl(origin)
      setStatus('connected')
      setError(null)
      return true
    } catch (cause) {
      return fail(toSessionError(cause, origin))
    }
  }, [])

  const disconnect = useCallback(() => {
    clientRef.current = null
    setClient(null)
    setStatus('disconnected')
    setError(null)
    // Cleared here only: a FAILED re-connect restores the live session and
    // must keep its workspace tenant.
    setTenantId('')
  }, [])

  const value = useMemo(
    () => ({ instanceUrl, status, error, client, tenantId, setTenantId, connect, disconnect }),
    [instanceUrl, status, error, client, tenantId, connect, disconnect],
  )
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext)
  if (value === null) {
    throw new Error('useSession must be used inside <SessionProvider>')
  }
  return value
}

type ParsedInstanceUrl = { kind: 'ok'; origin: string } | { kind: 'invalid' } | { kind: 'has-path' }

/**
 * Trim, default the scheme to http://, reject anything that is not http(s).
 * URLs carrying a path, query, or fragment are rejected explicitly instead
 * of silently stripped: the client and the dev proxy work origin-only, and
 * a swallowed `/prefix` would surface as a baffling 404 later.
 */
function parseInstanceUrl(raw: string): ParsedInstanceUrl {
  const trimmed = raw.trim()
  if (trimmed === '') return { kind: 'invalid' }
  const candidate = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`
  try {
    const url = new URL(candidate)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return { kind: 'invalid' }
    if (url.pathname !== '/' || url.search !== '' || url.hash !== '') return { kind: 'has-path' }
    return { kind: 'ok', origin: url.origin }
  } catch {
    return { kind: 'invalid' }
  }
}

function toSessionError(cause: unknown, origin: string): SessionError {
  if (cause instanceof ApiError) {
    if (cause.status === 401) {
      return {
        kind: 'unauthorized',
        message:
          'The instance is up but rejected this API key. Check the key — the root operator ' +
          'key is printed once (WARN) in the API log at first startup.',
        requestId: cause.requestId,
      }
    }
    if (cause.code === 'upstream_unreachable') {
      return {
        kind: 'unreachable',
        message:
          `Nothing answered at ${origin}. Is the instance running? ` +
          'If it is, try 127.0.0.1 instead of localhost.',
      }
    }
    if (cause.code === 'upstream_timeout') {
      return {
        kind: 'http',
        message:
          `The instance at ${origin} was reached but took too long to answer ` +
          '(dev-proxy timeout). It may be busy — try again.',
      }
    }
    if (cause.status === 503) {
      // Spring Boot Actuator maps health DOWN / OUT_OF_SERVICE to 503, and
      // that body is not the error envelope — this is the standard way a
      // real-but-unhealthy instance answers the probe.
      return {
        kind: 'http',
        message:
          'The instance answered but reports itself unhealthy (the health probe returned ' +
          '503). Check its dependencies — database, Redis.',
        requestId: cause.requestId,
      }
    }
    // Synthesized envelopes ("http_502": "502 Bad Gateway") already start
    // with the status code — do not double it.
    const text = cause.message.startsWith(String(cause.status))
      ? cause.message
      : `${cause.status}: ${cause.message}`
    return { kind: 'http', message: text, requestId: cause.requestId }
  }
  if (cause instanceof NetworkError) {
    return {
      kind: 'unreachable',
      message:
        'The request never reached an instance. Is the playground dev server running ' +
        '(its proxy does the forwarding), and the instance URL correct?',
    }
  }
  return {
    kind: 'unexpected',
    message: cause instanceof Error ? cause.message : String(cause),
  }
}
