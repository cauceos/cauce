import { ApiError, NetworkError } from '../api/errors'
import type { FieldError } from '../api/types'

/**
 * A create-form's view of a failed submission: the field-level violations
 * (rendered inline as `field · message`) plus a single fallback line for
 * everything else (tier rule, network, unexpected). Exactly one of the two
 * is typically populated, but both render if the backend sends both.
 */
export interface FormError {
  fields: FieldError[]
  message: string | null
}

export function toFormError(cause: unknown): FormError {
  if (cause instanceof ApiError) {
    const fields = cause.envelope.errors ?? []
    // With field violations present, the top-level message is a generic
    // "validation failed" — the fields say more, so suppress the duplicate.
    return { fields, message: fields.length > 0 ? null : cause.message }
  }
  if (cause instanceof NetworkError) {
    return { fields: [], message: 'request failed — is the instance still reachable?' }
  }
  return { fields: [], message: cause instanceof Error ? cause.message : String(cause) }
}
