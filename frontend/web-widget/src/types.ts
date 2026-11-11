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

/** A signal the browser computes for the shop-assistant mode (ADR 0014, WC8). */
export type Signal = 'back_to_product' | 'comparing' | 'idle_cart' | 'exit_intent'

export interface Route {
  path: string
  signals: Signal[]
}

export interface Variant {
  id: string
  bubble: string | null
  templates: Partial<Record<Signal, string>> | null
  signals: Signal[] | null
}

/** The shop-assistant mode (WC8): present only while the channel turns it on. */
export interface Assistant {
  enabled: boolean
  bubble: string | null
  card_button_label: string | null
  routes: Route[]
  tuning: { comparing_views: number; comparing_minutes: number; idle_cart_minutes: number }
  templates: Partial<Record<Signal, string>>
  variants: Variant[]
  /** Off: the bubble alone, no signal opens the chat (the first phase of a launch). */
  proactive_opening: boolean
  proactive_requires_consent: boolean
  opening_counter: boolean
  opening_counter_requires_consent: boolean
  /** Off by default; on, a purchase is reported only once the visitor consented. */
  purchase_attribution: boolean
}

export interface ChannelConfig {
  public_key: string
  turnstile_site_key: string
  privacy_policy_url: string
  consent_mode: 'NOTICE' | 'ACCEPTANCE'
  consent_required: boolean
  active: boolean
  appearance: Appearance
  assistant?: Assistant | null
}

/** A product card of a reply, as the catalog fills it in now (WC8). */
export interface Card {
  ref: string
  name: string
  description: string | null
  price: number | null
}

export interface ChatMessage {
  id: string
  role: 'user' | 'agent'
  text: string
  created_at: string
  cards?: Card[]
}

export interface CartLine {
  ref: string
  quantity: number
}

/** Where the person is, as the shop's page tells the widget: refs only, never free text. */
export interface PageContext {
  path: string
  product_ref?: string
  cart?: CartLine[]
}

/** How the chat was opened before a message, and the variant drawn. */
export interface Opening {
  kind: 'proactive' | 'on_demand'
  signal?: Signal
  variant?: string
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
