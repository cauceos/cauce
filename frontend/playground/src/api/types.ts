/**
 * Wire types for the Cauce REST surface. JSON is snake_case globally on the
 * backend; fields keep their wire names verbatim — no mapping layer.
 */

/** A single field-level validation violation (input-validation failures only). */
export interface FieldError {
  field: string
  message: string
}

/**
 * The uniform error body of the whole REST surface
 * (backend `ErrorResponse`): `error` is a stable machine-readable code,
 * `request_id` correlates with the server logs. The dev proxy synthesizes
 * the same shape for its own errors (`proxy_target_missing`,
 * `upstream_unreachable`).
 */
export interface ErrorEnvelope {
  error: string
  message: string
  request_id: string | null
  errors?: FieldError[]
}

/** Spring Boot actuator health body. */
export interface HealthResponse {
  status: string
}
