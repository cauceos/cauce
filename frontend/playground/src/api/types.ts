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
 * Structured tool payload of a TOOL_CALL / TOOL_RESULT message (backend
 * `ToolContentResponse`, NON_NULL serialization): a call carries `input`,
 * a result carries `output` + `is_error` — the other variant's keys are
 * omitted, so the message `role` is the discriminator.
 */
export interface ToolContentResponse {
  tool_call_id: string
  tool_name: string
  input?: Record<string, unknown>
  output?: string
  is_error?: boolean
}

/**
 * A thread message. `tool_content` travels only on TOOL_CALL / TOOL_RESULT
 * messages (omitted entirely for text roles, and absent on older
 * instances); `content` still flattens them (tool name / output text). The
 * wire carries no invocation link — a known, accepted gap.
 */
export interface MessageResponse {
  id: string
  role: MessageRole
  content: string
  tool_content?: ToolContentResponse
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

/**
 * Body of GET /v1/me — who the API key is, per the backend `MeResponse`.
 * The identity authentication already established: nothing here widens
 * visibility beyond what the key could already reach.
 */
export interface MeResponse {
  tenant_id: string
  tenant_name: string
  tier: Tier
  key_id: string
}

/** Derived server-side from revoked_at / expires_at (backend `ApiKeyResponse.status`). */
export type ApiKeyStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED'

/**
 * Metadata of an API key (backend `ApiKeyResponse`). The plaintext and the
 * hash are never here — the plaintext is returned exactly once, at
 * creation, in `ApiKeyCreatedResponse`. `key_prefix` is the first eight
 * characters of the plaintext (`ck_` + five), the part a holder can
 * recognise the key by; `label` is the optional human name given at issue.
 * `last_used_at` is approximate: updated on the auth cold path only.
 */
export interface ApiKeyResponse {
  id: string
  tenant_id: string
  key_prefix: string
  label: string | null
  status: ApiKeyStatus
  created_at: string
  last_used_at: string | null
  revoked_at: string | null
  expires_at: string | null
}

/** 201 body of POST /v1/tenants/{tenantId}/api-keys — `api_key` is shown once. */
export interface ApiKeyCreatedResponse {
  api_key: string
  id: string
  tenant_id: string
  key_prefix: string
  label: string | null
  status: ApiKeyStatus
  created_at: string
}

/** Request body for issuing a key; the label is optional. */
export interface CreateApiKeyBody {
  label?: string
}

/**
 * Outcome of recomputing a tenant's audit chain. VALID states only that
 * no break is detectable by re-hashing — what that does and does not
 * cover is spelled out in `verification_scope` on every response.
 */
export type ChainStatus = 'VALID' | 'BROKEN'

/**
 * The five public break classifications. Typed as the union plus `string`
 * so a value this build does not know still arrives intact and renders
 * as-is instead of being narrowed away or mapped to a guess.
 */
export type BreakClassification =
  | 'ENTRY_ALTERED'
  | 'LINK_BROKEN'
  | 'ENTRY_MISSING'
  | 'UNCHAINED_ENTRY_OUT_OF_ORDER'
  | 'ENTRY_MALFORMED'
  | (string & {})

/** Where and how the chain first breaks. Present only when status is BROKEN. */
export interface FirstBreak {
  sequence_number: number
  classification: BreakClassification
}

/**
 * What the verification covers, in the API's own words. Rendered verbatim
 * by the client — never paraphrased, never hardcoded: if the backend's
 * capability changes, its description changes with it.
 */
export interface VerificationScope {
  method: string
  detects: string[]
  does_not_detect: string
}

/** Body of GET /v1/tenants/{tenantId}/audit/chain-verification. */
export interface ChainVerificationResponse {
  tenant_id: string
  status: ChainStatus
  verified_entries: number
  /** Rows written before chaining began: not failures, and not verified. */
  pre_chain_entries: number
  first_break: FirstBreak | null
  verification_scope: VerificationScope
  verified_at: string
}

/** 202 body of POST /v1/agents/{agentId}/messages. */
export interface SendMessageAccepted {
  conversation_id: string
  message_id: string
  invocation_id: string
}
