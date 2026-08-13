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

/**
 * Uniform keyset-pagination envelope of the list endpoints.
 * `next_cursor` is explicitly null on the last page (never omitted).
 */
export interface CursorPage<T> {
  data: T[]
  next_cursor: string | null
}

/** Backend enum names, serialized verbatim. */
export type MessageRole = 'USER' | 'AGENT' | 'SYSTEM' | 'TOOL_CALL' | 'TOOL_RESULT'
export type ConversationStatus = 'OPEN' | 'CLOSED' | 'ESCALATED' | 'ARCHIVED'
export type AgentStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ARCHIVED'
/** The three fixed tiers of the tenant hierarchy (enum name, verbatim). */
export type Tier = 'OPERATOR' | 'PARTNER' | 'CLIENT'
/** Public invocation vocabulary (internal ABANDONED collapses into FAILED). */
export type InvocationStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
export type FailureReason =
  | 'PROVIDER_ERROR'
  | 'PROVIDER_UNAVAILABLE'
  | 'AGENT_LOOP_LIMIT'
  | 'INTERNAL_ERROR'
  | 'TIMEOUT'

/**
 * API representation of a tenant. `parent_tenant_id` is null for the
 * operator (the hierarchy root). `tier` is the enum name, verbatim.
 */
export interface TenantResponse {
  id: string
  parent_tenant_id: string | null
  tier: Tier
  name: string
  created_at: string
  updated_at: string
}

export interface AgentResponse {
  id: string
  tenant_id: string
  name: string
  system_prompt: string
  model_provider: string
  model_name: string
  temperature: number
  max_response_tokens: number
  status: AgentStatus
  created_at: string
  updated_at: string
}

/**
 * Request body for creating an agent. `temperature` and
 * `max_response_tokens` are optional: omitted → the domain applies its
 * defaults (verified against `CreateAgentRequest`).
 */
export interface CreateAgentBody {
  name: string
  system_prompt: string
  model_provider: string
  model_name: string
  temperature?: number
  max_response_tokens?: number
}

export interface ConversationResponse {
  id: string
  agent_id: string
  channel_type: string
  external_identity_ref: string
  status: ConversationStatus
  started_at: string
  last_message_at: string | null
  closed_at: string | null
  escalated_at: string | null
  archived_at: string | null
}

/**
 * A thread message. The wire carries no `tool_content` and no invocation
 * link: for TOOL_CALL messages `content` is the tool name, for TOOL_RESULT
 * it is the output text (`"[empty result]"` when blank).
 */
export interface MessageResponse {
  id: string
  role: MessageRole
  content: string
  created_at: string
}

export interface InvocationResponse {
  id: string
  conversation_id: string
  trigger_message_id: string
  status: InvocationStatus
  /** Only on permanent failure; null for pre-V17 rows even then. */
  failure_reason: FailureReason | null
  created_at: string
  completed_at: string | null
}

/** 202 body of POST /v1/agents/{agentId}/messages. */
export interface SendMessageAccepted {
  conversation_id: string
  message_id: string
  invocation_id: string
}
