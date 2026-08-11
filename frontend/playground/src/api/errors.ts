import type { ErrorEnvelope } from './types'

/**
 * A non-2xx HTTP response carrying the backend's uniform error envelope —
 * or an envelope synthesized from a non-JSON body, so callers always get
 * one shape.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly envelope: ErrorEnvelope,
  ) {
    super(envelope.message)
    this.name = 'ApiError'
  }

  /** The stable machine-readable code (`invocation_not_found`, `invalid_cursor`, …). */
  get code(): string {
    return this.envelope.error
  }

  get requestId(): string | null {
    return this.envelope.request_id
  }
}

/**
 * The request never produced an HTTP response: fetch rejected (dev server
 * down, request timed out, connection dropped).
 */
export class NetworkError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NetworkError'
  }
}
