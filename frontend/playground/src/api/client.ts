import { ApiError, NetworkError } from './errors'
import type { ErrorEnvelope, HealthResponse } from './types'

/** Header naming the real instance for the dev proxy (see vite.config.ts). */
const TARGET_HEADER = 'X-Cauce-Target'
const REQUEST_TIMEOUT_MS = 30_000

/**
 * Central HTTP client for the playground — every area goes through it.
 *
 * Requests are sent to the same-origin dev proxy (`/proxy/*`) with the
 * instance URL in a header, because the backend has no CORS configuration
 * and the browser cannot call it cross-origin directly. Every request
 * carries `Authorization: Bearer <key>`.
 *
 * The API key lives only inside this instance, in React state — it is
 * never written to localStorage, sessionStorage, or cookies.
 */
export class ApiClient {
  constructor(
    /** Origin of the Cauce instance, e.g. `http://localhost:8080`. */
    readonly instanceUrl: string,
    private readonly apiKey: string,
  ) {}

  /**
   * GET /actuator/health — the connect probe. The path is public, but the
   * Bearer header is always attached and the backend rejects an invalid
   * key with 401 even on public paths, so this validates reachability AND
   * the API key in one call.
   */
  health(): Promise<HealthResponse> {
    return this.request<HealthResponse>('/actuator/health')
  }

  async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set(TARGET_HEADER, this.instanceUrl)
    headers.set('Authorization', `Bearer ${this.apiKey}`)
    if (init.body != null && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }

    let response: Response
    try {
      response = await fetch(`/proxy${path}`, {
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
        ...init,
        headers,
      })
    } catch (cause) {
      throw new NetworkError(cause instanceof Error ? cause.message : String(cause))
    }

    if (!response.ok) {
      throw new ApiError(response.status, await parseEnvelope(response))
    }
    if (response.status === 204) {
      return undefined as T
    }
    try {
      return (await response.json()) as T
    } catch {
      throw new ApiError(response.status, {
        error: 'invalid_response',
        message: 'The server answered 2xx but not with JSON — is this really a Cauce instance?',
        request_id: null,
      })
    }
  }
}

async function parseEnvelope(response: Response): Promise<ErrorEnvelope> {
  try {
    const body: unknown = await response.json()
    if (
      typeof body === 'object' &&
      body !== null &&
      typeof (body as { error?: unknown }).error === 'string' &&
      typeof (body as { message?: unknown }).message === 'string'
    ) {
      return body as ErrorEnvelope
    }
  } catch {
    // Non-JSON body (e.g. an HTML error page from a non-Cauce upstream).
  }
  return {
    error: `http_${response.status}`,
    message: `${response.status} ${response.statusText}`.trim(),
    request_id: null,
  }
}
