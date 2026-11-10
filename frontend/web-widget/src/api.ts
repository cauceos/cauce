import type { ChannelConfig, MessagePage, Sent } from './types'

/**
 * Why a request did not succeed, as the widget acts on it. A person never sees any of these words:
 * 'unavailable' shows the channel's friendly message and contact, never a technical error.
 */
export type Failure =
  | { kind: 'unavailable' }
  | { kind: 'rate_limited'; retryAfterSeconds: number }
  | { kind: 'session' }
  | { kind: 'thread_full' }
  | { kind: 'consent_required' }
  | { kind: 'invalid' }

export class WidgetError extends Error {
  readonly failure: Failure

  constructor(failure: Failure) {
    super(failure.kind)
    this.failure = failure
  }
}

/** Maps a response that did not succeed to what the widget does about it. */
export async function failureOf(response: Response): Promise<Failure> {
  let code = ''
  try {
    code = (await response.json())?.error ?? ''
  } catch {
    // not JSON: decided by the status alone
  }
  if (response.status === 429) {
    const seconds = Number(response.headers.get('Retry-After'))
    return { kind: 'rate_limited', retryAfterSeconds: Number.isFinite(seconds) && seconds > 0 ? seconds : 30 }
  }
  if (response.status === 401) return { kind: 'session' }
  if (code === 'thread_full') return { kind: 'thread_full' }
  if (code === 'consent_required') return { kind: 'consent_required' }
  if (response.status === 400 && code === 'invalid_message') return { kind: 'invalid' }
  // channel_disabled, rate_limit_unavailable, turnstile_failed or _unavailable, a 5xx, a 403:
  // the person can do nothing about them, so they read as "not available right now".
  return { kind: 'unavailable' }
}

/** The public web-chat surface of one channel: /web/v1/{publicKey}/... on the Cauce instance. */
export class WebChatApi {
  private readonly base: string

  constructor(instanceUrl: string, publicKey: string) {
    this.base = instanceUrl.replace(/\/+$/, '') + '/web/v1/' + encodeURIComponent(publicKey)
  }

  config(): Promise<ChannelConfig> {
    return this.request<ChannelConfig>('/config', { method: 'GET' })
  }

  openSession(turnstileToken: string, consent: boolean | undefined, noticeVersion: string): Promise<{ session_token: string }> {
    const body: Record<string, unknown> = { turnstile_token: turnstileToken }
    if (consent) {
      body.consent = true
      body.notice_version = noticeVersion
    }
    return this.request('/sessions', { method: 'POST', body: JSON.stringify(body) })
  }

  send(token: string, content: string, newThread: boolean, idempotencyKey: string): Promise<Sent> {
    return this.request('/messages', {
      method: 'POST',
      body: JSON.stringify(newThread ? { content, new_thread: true } : { content }),
      headers: { Authorization: 'Bearer ' + token, 'Idempotency-Key': idempotencyKey },
    })
  }

  read(token: string, cursor: string | null): Promise<MessagePage> {
    const query = cursor ? '?cursor=' + encodeURIComponent(cursor) : ''
    return this.request('/messages' + query, { method: 'GET', headers: { Authorization: 'Bearer ' + token } })
  }

  private async request<T>(path: string, init: RequestInit & { headers?: Record<string, string> }): Promise<T> {
    let response: Response
    try {
      response = await fetch(this.base + path, {
        ...init,
        headers: { ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...(init.headers ?? {}) },
        credentials: 'omit',
        mode: 'cors',
      })
    } catch {
      throw new WidgetError({ kind: 'unavailable' })
    }
    if (!response.ok) {
      throw new WidgetError(await failureOf(response))
    }
    return (await response.json()) as T
  }
}
