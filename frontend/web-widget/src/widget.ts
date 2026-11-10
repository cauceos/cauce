import { WebChatApi, WidgetError, type Failure } from './api'
import { ShopAssistant } from './assistant'
import * as storage from './storage'
import { STYLES, themeOf } from './styles'
import { NOTICE_VERSION, TEXTS, type Texts } from './texts'
import { turnstileToken } from './turnstile'
import type { Card, CartLine, ChannelConfig, ChatMessage, MessagePage, Opening, PageContext, Signal } from './types'

/** How often the widget polls (ADR 0014, WC4): soon while a reply is coming, slowly after, then not. */
export const POLL_AWAITING_MS = 1500
export const POLL_IDLE_MS = 10_000
export const POLL_STOP_AFTER_MS = 2 * 60_000

export interface Dependencies {
  api: WebChatApi
  turnstile: (siteKey: string, locale: string) => Promise<string>
  now: () => number
  schedule: (fn: () => void, ms: number) => unknown
  cancel: (handle: unknown) => void
  newKey: () => string
  random: () => number
  path: () => string
  finePointer: () => boolean
}

/** What the shop's page hands the widget's "add to cart" button: the card's ref and how many. */
export type AddToCart = (ref: string, quantity: number) => void

const CHAT_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path fill="currentColor" d="M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2z"/></svg>'
const CLOSE_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path fill="currentColor" d="M18.3 5.7 12 12l6.3 6.3-1.4 1.4L10.6 13.4 4.3 19.7 2.9 18.3 9.2 12 2.9 5.7 4.3 4.3l6.3 6.3 6.3-6.3z"/></svg>'

function el<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Record<string, string> = {}, text?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag)
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v)
  if (text !== undefined) node.textContent = text
  return node
}

/**
 * The web chat in the shop's page (ADR 0014, W4): a launcher and a non-modal dialog in its own Shadow
 * DOM, so neither the page's styles nor its scripts reach inside. Before the person's first message,
 * nothing about them leaves the browser: the session, and the Turnstile check it needs, wait for it.
 */
export class Widget {
  readonly host: HTMLElement
  private readonly root: ShadowRoot
  private readonly t: Texts
  private readonly key: string
  private token: string | null
  private cursor: string | null = null
  private conversationId: string | null = null
  private readonly shown = new Set<string>()
  private pollHandle: unknown = null
  private lastActivity = 0
  private sending = false
  private readonly assistant: ShopAssistant | null
  private product: string | undefined
  private cart: CartLine[] = []
  private addToCart: AddToCart | null = null
  private pendingOpening: Opening | null = null

  private launcher!: HTMLButtonElement
  private presence: HTMLElement | null = null
  private panel!: HTMLElement
  private log!: HTMLOListElement
  private status!: HTMLElement
  private notice!: HTMLElement
  private input!: HTMLTextAreaElement
  private sendButton!: HTMLButtonElement
  private accept: HTMLInputElement | null = null

  constructor(private readonly config: ChannelConfig, private readonly deps: Dependencies) {
    this.t = TEXTS[config.appearance.locale] ?? TEXTS.es
    this.key = config.public_key
    this.token = storage.load(this.key, 'session')
    this.host = el('div', { 'data-cauce-chat': '' })
    this.root = this.host.attachShadow({ mode: 'open' })
    const mode = config.assistant
    this.assistant = mode && mode.enabled ? new ShopAssistant(mode, this.key, deps) : null
    this.render()
    if (this.assistant) {
      this.assistant.whenSignalled((signal, greeting) => this.openBySignal(signal, greeting))
      document.addEventListener('mouseout', (e) => {
        if (!e.relatedTarget && e.clientY <= 0) this.assistant?.exitIntent()
      })
    }
  }

  private render(): void {
    const a = this.config.appearance
    const style = el('style')
    style.textContent = themeOf(a) + STYLES
    const root = el('div', { class: 'root' })

    this.launcher = el('button', { type: 'button', class: 'launcher', 'aria-expanded': 'false',
      'aria-controls': 'cauce-panel', 'aria-label': a.launcher_label ?? this.t.open })
    this.launcher.innerHTML = a.avatar_url ? '' : CHAT_ICON
    if (a.avatar_url) this.launcher.append(el('img', { src: a.avatar_url, alt: '' }))
    this.launcher.append(el('span', { 'aria-hidden': 'true' }, a.launcher_label ?? a.name))
    this.launcher.addEventListener('click', () => (this.panel.hidden ? this.open() : this.close()))
    const phrase = this.assistant?.bubble()
    if (phrase) {
      // The presence bubble (WC8): static, from the configuration, beside the launcher, never over the page.
      this.presence = el('div', { class: 'presence' })
      const say = el('button', { type: 'button', class: 'say' }, phrase)
      say.addEventListener('click', () => this.open())
      const dismiss = el('button', { type: 'button', class: 'icon', 'aria-label': this.t.dismiss })
      dismiss.innerHTML = CLOSE_ICON
      dismiss.addEventListener('click', () => this.hidePresence())
      this.presence.append(say, dismiss)
    }

    this.panel = el('section', { id: 'cauce-panel', class: 'panel', role: 'dialog', 'aria-modal': 'false',
      'aria-labelledby': 'cauce-title', 'aria-describedby': 'cauce-ai' })
    this.panel.hidden = true
    this.panel.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        this.close()
      }
    })

    const head = el('header', { class: 'head' })
    if (a.avatar_url) head.append(el('img', { src: a.avatar_url, alt: '' }))
    const titles = el('div', { class: 'titles' })
    titles.append(el('h2', { id: 'cauce-title' }, a.name), el('p', { id: 'cauce-ai', class: 'ai' }, this.t.aiNotice))
    const closeButton = el('button', { type: 'button', class: 'icon', 'aria-label': this.t.close })
    closeButton.innerHTML = CLOSE_ICON
    closeButton.addEventListener('click', () => this.close())
    head.append(titles, closeButton)

    this.log = el('ol', { class: 'log', role: 'log', 'aria-live': 'polite', 'aria-relevant': 'additions',
      'aria-label': a.name })
    if (a.greeting) this.log.append(this.bubble('agent', a.greeting))

    this.status = el('p', { class: 'status', role: 'status' })
    this.notice = el('div', { class: 'notice', role: 'alert' })
    this.notice.hidden = true

    const form = el('form', { class: 'composer' })
    this.input = el('textarea', { id: 'cauce-input', rows: '1', maxlength: '2000',
      placeholder: a.placeholder ?? this.t.placeholder, 'aria-describedby': 'cauce-ai' })
    const label = el('label', { for: 'cauce-input', class: 'sr' }, this.t.messageLabel)
    this.sendButton = el('button', { type: 'submit', class: 'btn' }, this.t.send)
    this.input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
        e.preventDefault()
        form.requestSubmit()
      }
    })
    form.addEventListener('submit', (e) => {
      e.preventDefault()
      void this.submit(false)
    })
    form.append(label, this.input, this.sendButton)

    const privacy = this.privacyBlock()
    this.panel.append(head, this.log, this.status, this.notice, privacy, form)
    root.append(...(this.presence ? [this.presence] : []), this.launcher, this.panel)
    this.root.append(style, root)
  }

  private privacyBlock(): HTMLElement {
    const link = el('a', { href: this.config.privacy_policy_url, target: '_blank', rel: 'noopener noreferrer' },
      this.t.privacyLink)
    if (this.config.consent_required) {
      const wrap = el('div', { class: 'accept' })
      this.accept = el('input', { type: 'checkbox', id: 'cauce-accept' })
      const label = el('label', { for: 'cauce-accept' }, this.t.acceptPrivacy + ' ')
      label.append('(', link, ')')
      wrap.append(this.accept, label)
      return wrap
    }
    const p = el('p', { class: 'privacy' }, this.t.privacyNotice + ' ')
    p.append(link)
    return p
  }

  /** Opens the chat because the person asked: the launcher, the bubble, or the shop's page on their behalf. */
  open(): void {
    this.openAs({ kind: 'on_demand' })
  }

  /** Closes the chat. The person closed it: with the shop-assistant mode, it never opens by itself again. */
  close(): void {
    this.panel.hidden = true
    this.launcher.setAttribute('aria-expanded', 'false')
    this.stopPolling()
    this.assistant?.closedByPerson()
    this.launcher.focus()
  }

  private openAs(opening: Opening): void {
    if (!this.panel.hidden) return
    this.panel.hidden = false
    this.launcher.setAttribute('aria-expanded', 'true')
    this.hidePresence()
    if (this.assistant) {
      this.assistant.opened()
      this.pendingOpening = { ...opening, variant: this.assistant.variantId() }
      if (this.assistant.mayCountOpenings()) {
        void this.deps.api.opening(this.pendingOpening).catch(() => {})
      }
    }
    this.input.focus()
    if (this.token) this.touch()
  }

  /** A signal opened the chat (WC8): its greeting is the configuration's template, not a model's reply. */
  private openBySignal(signal: Signal, greeting: string): void {
    if (!this.panel.hidden) return
    this.log.append(this.bubble('agent', greeting))
    this.openAs({ kind: 'proactive', signal })
  }

  private hidePresence(): void {
    if (this.presence) this.presence.hidden = true
  }

  // --- what the shop's page tells the widget (WC8): refs and numbers only, never free text ---

  /** The product on screen, by catalog ref; a view of it may signal. */
  viewProduct(ref: string): void {
    this.product = ref
    this.assistant?.viewProduct(ref)
  }

  /** The shop's cart, by catalog ref; one left unchanged may signal. */
  setCart(lines: CartLine[]): void {
    this.cart = lines.filter((l) => typeof l?.ref === 'string' && Number.isInteger(l.quantity) && l.quantity > 0)
    this.assistant?.setCart(this.cart)
  }

  /** What a card's button does: the shop's own hook. Without one, a card shows no button. */
  onAddToCart(hook: AddToCart): void {
    this.addToCart = hook
  }

  /** The visitor's consent, from the shop's consent banner, for what the channel makes wait for it. */
  consent(granted: boolean): void {
    this.assistant?.consent(granted)
  }

  /** A purchase the shop confirms: reported only when this browser has talked to the chat. */
  trackPurchase(purchase: { orderId: string; value: number }): void {
    if (!this.token || !purchase || typeof purchase.orderId !== 'string' || typeof purchase.value !== 'number') return
    void this.deps.api.event(this.token, { type: 'purchase', order_id: purchase.orderId, value: purchase.value,
      variant: this.assistant?.variantId() }).catch(() => {})
  }

  private context(): PageContext {
    const context: PageContext = { path: this.deps.path() }
    if (this.product) context.product_ref = this.product
    if (this.cart.length > 0) context.cart = this.cart
    return context
  }

  /** Sends what the person wrote; with {@code newThread}, starts a new conversation first. */
  async submit(newThread: boolean): Promise<void> {
    const text = this.input.value.trim()
    if (!text || this.sending) return
    if (this.accept && !this.accept.checked) {
      this.showNotice(this.t.acceptFirst)
      this.accept.focus()
      return
    }
    this.sending = true
    this.sendButton.disabled = true
    this.hideNotice()
    try {
      await this.sendWithSession(text, newThread)
      this.input.value = ''
      if (newThread) this.resetThread()
      this.touch()
    } catch (e) {
      this.fail(e instanceof WidgetError ? e.failure : { kind: 'unavailable' })
    } finally {
      this.sending = false
      this.sendButton.disabled = false
    }
  }

  private async sendWithSession(text: string, newThread: boolean): Promise<void> {
    const key = this.deps.newKey()
    for (let attempt = 0; attempt < 2; attempt++) {
      const token = await this.session()
      try {
        await this.deps.api.send(token, text, newThread, key, this.assistant
          ? { context: this.context(), opening: this.pendingOpening ?? undefined } : {})
        this.pendingOpening = null
        return
      } catch (e) {
        if (e instanceof WidgetError && e.failure.kind === 'session' && attempt === 0) {
          this.dropSession() // expired or unknown: a fresh one, once
          continue
        }
        throw e
      }
    }
  }

  private async session(): Promise<string> {
    if (this.token) return this.token
    let challenge: string
    try {
      challenge = await this.deps.turnstile(this.config.turnstile_site_key, this.config.appearance.locale)
    } catch {
      throw new WidgetError({ kind: 'unavailable' })
    }
    const opened = await this.deps.api.openSession(challenge, this.accept?.checked,
      NOTICE_VERSION[this.config.appearance.locale] ?? NOTICE_VERSION.es)
    this.token = opened.session_token
    storage.save(this.key, 'session', this.token)
    return this.token
  }

  private dropSession(): void {
    this.token = null
    storage.forget(this.key, 'session')
  }

  /** Marks activity and polls now: a send, an open, a new reply. */
  private touch(): void {
    this.lastActivity = this.deps.now()
    this.stopPolling()
    void this.poll()
  }

  private async poll(): Promise<void> {
    if (!this.token) return
    let page: MessagePage
    try {
      page = await this.deps.api.read(this.token, this.cursor)
    } catch (e) {
      const failure = e instanceof WidgetError ? e.failure : { kind: 'unavailable' as const }
      if (failure.kind === 'session') this.dropSession()
      else if (failure.kind !== 'rate_limited') this.fail(failure)
      this.next(false, failure.kind === 'rate_limited')
      return
    }
    if (page.conversation_id && page.conversation_id !== this.conversationId) {
      if (this.conversationId !== null) this.resetThread()
      this.conversationId = page.conversation_id
    }
    let fresh = false
    for (const m of page.data) fresh = this.append(m) || fresh
    if (page.next_cursor) this.cursor = page.next_cursor
    if (fresh) this.lastActivity = this.deps.now()
    this.status.textContent = page.awaiting_reply ? this.t.typing : ''
    this.next(page.awaiting_reply, false)
  }

  private next(awaiting: boolean, slowDown: boolean): void {
    if (this.panel.hidden) return
    const idle = this.deps.now() - this.lastActivity
    if (!awaiting && idle > POLL_STOP_AFTER_MS) return
    const delay = slowDown ? POLL_IDLE_MS : awaiting ? POLL_AWAITING_MS : POLL_IDLE_MS
    this.pollHandle = this.deps.schedule(() => void this.poll(), delay)
  }

  private stopPolling(): void {
    if (this.pollHandle !== null) this.deps.cancel(this.pollHandle)
    this.pollHandle = null
  }

  private resetThread(): void {
    this.cursor = null
    this.shown.clear()
    this.log.replaceChildren()
    const greeting = this.config.appearance.greeting
    if (greeting) this.log.append(this.bubble('agent', greeting))
  }

  private append(m: ChatMessage): boolean {
    if (this.shown.has(m.id)) return false
    this.shown.add(m.id)
    const li = this.bubble(m.role, m.text)
    if (m.role === 'agent' && m.cards && m.cards.length > 0) li.append(this.cards(m.cards))
    this.log.append(li)
    this.log.scrollTop = this.log.scrollHeight
    return true
  }

  /** A reply's product cards (WC8): name, price and description from the catalog, and the shop's button. */
  private cards(cards: Card[]): HTMLUListElement {
    const list = el('ul', { class: 'cards', 'aria-label': this.t.cards })
    const locale = this.config.appearance.locale === 'en' ? 'en-IE' : 'es-ES'
    for (const card of cards) {
      const item = el('li', { class: 'card' })
      item.append(el('strong', {}, card.name))
      if (card.price !== null && card.price !== undefined) {
        const price = new Intl.NumberFormat(locale, { style: 'currency', currency: 'EUR',
          minimumFractionDigits: Number.isInteger(card.price) ? 0 : 2 }).format(card.price)
        item.append(el('span', { class: 'price' }, price))
      }
      if (card.description) item.append(el('p', {}, card.description))
      if (this.addToCart) {
        const label = this.config.assistant?.card_button_label ?? this.t.addToCart
        const buy = el('button', { type: 'button', class: 'btn', 'aria-label': label + ': ' + card.name }, label)
        buy.addEventListener('click', () => {
          this.addToCart?.(card.ref, 1)
          if (this.token) {
            void this.deps.api.event(this.token, { type: 'add_to_cart', ref: card.ref, quantity: 1,
              variant: this.assistant?.variantId() }).catch(() => {})
          }
        })
        item.append(buy)
      }
      list.append(item)
    }
    return list
  }

  private bubble(role: 'user' | 'agent', text: string): HTMLLIElement {
    const li = el('li', { class: 'msg ' + role })
    li.append(el('span', { class: 'sr' }, (role === 'user' ? this.t.you : this.config.appearance.name) + ': '))
    li.append(document.createTextNode(text)) // text only: a reply never becomes markup
    return li
  }

  private fail(failure: Failure): void {
    switch (failure.kind) {
      case 'rate_limited':
        this.showNotice(this.t.rateLimited(failure.retryAfterSeconds))
        return
      case 'thread_full': {
        this.showNotice(this.t.threadFull)
        const again = el('button', { type: 'button', class: 'btn' }, this.t.startOver)
        again.addEventListener('click', () => void this.submit(true))
        this.notice.append(again)
        return
      }
      case 'consent_required':
        this.showNotice(this.t.acceptFirst)
        return
      case 'invalid':
        this.showNotice(this.t.invalid)
        return
      default:
        this.showUnavailable()
    }
  }

  /** The channel's friendly message and its contact; never a technical error (ADR 0014, W3). */
  private showUnavailable(): void {
    const a = this.config.appearance
    this.showNotice(a.unavailable_message ?? this.t.unavailable)
    if (a.contact) this.notice.append(el('p', {}, this.t.contactPrefix + ' ' + a.contact))
  }

  private showNotice(text: string): void {
    this.notice.replaceChildren(el('p', {}, text))
    this.notice.hidden = false
  }

  private hideNotice(): void {
    this.notice.hidden = true
    this.notice.replaceChildren()
  }

  // for tests
  get shadow(): ShadowRoot {
    return this.root
  }
}

export function defaultDependencies(api: WebChatApi): Dependencies {
  return {
    api,
    turnstile: turnstileToken,
    now: () => Date.now(),
    schedule: (fn, ms) => window.setTimeout(fn, ms),
    cancel: (handle) => window.clearTimeout(handle as number),
    newKey: () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(36).slice(2)),
    random: () => Math.random(),
    path: () => window.location.pathname,
    finePointer: () => window.matchMedia?.('(hover: hover) and (pointer: fine)').matches ?? false,
  }
}
