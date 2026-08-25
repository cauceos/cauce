import { ApiError, NetworkError } from './errors'
import type {
  AgentResponse,
  ConversationResponse,
  CreateAgentBody,
  CursorPage,
  ErrorEnvelope,
  HealthResponse,
  InvocationResponse,
  MeResponse,
  MessageResponse,
  SendMessageAccepted,
  TenantResponse,
} from './types'

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

  /**
   * GET /v1/me — key introspection: the caller's tenant id/name/tier and
   * key id, derived entirely from the authenticated key. 404s on older
   * instances without the endpoint — callers degrade gracefully.
   */
  me(signal?: AbortSignal): Promise<MeResponse> {
    return this.request<MeResponse>('/v1/me', { signal })
  }

  /** GET /v1/tenants/{id} — a single tenant (the tree root, or a lookup). */
  getTenant(tenantId: string, signal?: AbortSignal): Promise<TenantResponse> {
    return this.request<TenantResponse>(`/v1/tenants/${tenantId}`, { signal })
  }

  /** GET /v1/tenants/{id}/children — one keyset page of direct children. */
  listChildren(
    tenantId: string,
    opts: { limit?: number; cursor?: string } = {},
    signal?: AbortSignal,
  ): Promise<CursorPage<TenantResponse>> {
    return this.request<CursorPage<TenantResponse>>(
      `/v1/tenants/${tenantId}/children${pageQuery(opts)}`,
      { signal },
    )
  }

  /** POST /v1/tenants/partner → 201. The parent operator id comes from the tree node. */
  createPartner(body: { name: string; operator_id: string }): Promise<TenantResponse> {
    return this.request<TenantResponse>('/v1/tenants/partner', {
      method: 'POST',
      body: JSON.stringify(body),
    })
  }

  /** POST /v1/tenants/client → 201. The parent partner id comes from the tree node. */
  createClient(body: { name: string; partner_id: string }): Promise<TenantResponse> {
    return this.request<TenantResponse>('/v1/tenants/client', {
      method: 'POST',
      body: JSON.stringify(body),
    })
  }

  /** GET /v1/tenants/{tenantId}/agents — one keyset page. */
  listAgents(
    tenantId: string,
    opts: { limit?: number; cursor?: string } = {},
    signal?: AbortSignal,
  ): Promise<CursorPage<AgentResponse>> {
    return this.request<CursorPage<AgentResponse>>(
      `/v1/tenants/${tenantId}/agents${pageQuery(opts)}`,
      { signal },
    )
  }

  /** POST /v1/tenants/{tenantId}/agents → 201. */
  createAgent(tenantId: string, body: CreateAgentBody): Promise<AgentResponse> {
    return this.request<AgentResponse>(`/v1/tenants/${tenantId}/agents`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  }

  /**
   * POST /v1/agents/{agentId}/messages → 202. The optional Idempotency-Key
   * makes a network-level retry of the same submission replay the original
   * 202 instead of ingesting a duplicate.
   */
  sendMessage(
    agentId: string,
    body: { external_identity_ref: string; content: string },
    idempotencyKey?: string,
  ): Promise<SendMessageAccepted> {
    return this.request<SendMessageAccepted>(`/v1/agents/${agentId}/messages`, {
      method: 'POST',
      body: JSON.stringify(body),
      headers: idempotencyKey != null ? { 'Idempotency-Key': idempotencyKey } : undefined,
    })
  }

  /** GET /v1/conversations/{id}. */
  getConversation(conversationId: string, signal?: AbortSignal): Promise<ConversationResponse> {
    return this.request<ConversationResponse>(`/v1/conversations/${conversationId}`, { signal })
  }

  /**
   * GET /v1/conversations/{id}/messages — one keyset page, ascending by id,
   * forward-only (`cursor` = id of the last message already held).
   */
  listMessages(
    conversationId: string,
    opts: { limit?: number; cursor?: string } = {},
    signal?: AbortSignal,
  ): Promise<CursorPage<MessageResponse>> {
    return this.request<CursorPage<MessageResponse>>(
      `/v1/conversations/${conversationId}/messages${pageQuery(opts)}`,
      { signal },
    )
  }

  /** GET /v1/invocations/{id} — processing status, for polling after a 202. */
  getInvocation(invocationId: string, signal?: AbortSignal): Promise<InvocationResponse> {
    return this.request<InvocationResponse>(`/v1/invocations/${invocationId}`, { signal })
  }

  async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set(TARGET_HEADER, this.instanceUrl)
    headers.set('Authorization', `Bearer ${this.apiKey}`)
    if (init.body != null && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }

    // A caller-provided signal (poll cancellation) must combine with the
    // 30s timeout, not replace it.
    const signal =
      init.signal != null
        ? AbortSignal.any([AbortSignal.timeout(REQUEST_TIMEOUT_MS), init.signal])
        : AbortSignal.timeout(REQUEST_TIMEOUT_MS)

    let response: Response
    try {
      response = await fetch(`/proxy${path}`, {
        ...init,
        signal,
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

function pageQuery({ limit, cursor }: { limit?: number; cursor?: string }): string {
  const params = new URLSearchParams()
  if (limit != null) params.set('limit', String(limit))
  if (cursor != null) params.set('cursor', cursor)
  const query = params.toString()
  return query === '' ? '' : `?${query}`
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
