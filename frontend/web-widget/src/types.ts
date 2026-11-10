// The wire shapes of the public web-chat surface (ADR 0014), snake_case as the API sends them.

export interface Appearance {
  name: string
  avatar_url: string | null
  primary_color: string
  on_primary_color: string
  background_color: string
  text_color: string
  font_family: string
  greeting: string | null
  placeholder: string | null
  launcher_label: string | null
  unavailable_message: string | null
  contact: string | null
  locale: 'es' | 'en'
}

export interface ChannelConfig {
  public_key: string
  turnstile_site_key: string
  privacy_policy_url: string
  consent_mode: 'NOTICE' | 'ACCEPTANCE'
  consent_required: boolean
  active: boolean
  appearance: Appearance
}

export interface ChatMessage {
  id: string
  role: 'user' | 'agent'
  text: string
  created_at: string
}

export interface MessagePage {
  conversation_id: string | null
  data: ChatMessage[]
  next_cursor: string | null
  awaiting_reply: boolean
}

export interface Sent {
  conversation_id: string
  message_id: string
  invocation_id: string
}
