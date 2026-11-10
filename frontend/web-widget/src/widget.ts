import { WebChatApi, WidgetError, type Failure } from './api'
import * as storage from './storage'
import { STYLES, themeOf } from './styles'
import { NOTICE_VERSION, TEXTS, type Texts } from './texts'
import { turnstileToken } from './turnstile'
import type { ChannelConfig, ChatMessage, MessagePage } from './types'

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
}

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

  private launcher!: HTMLButtonElement
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
    this.render()
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
    root.append(this.launcher, this.panel)
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

  open(): void {
    this.panel.hidden = false
    this.launcher.setAttribute('aria-expanded', 'true')
    this.input.focus()
    if (this.token) this.touch()
  }

  close(): void {
    this.panel.hidden = true
    this.launcher.setAttribute('aria-expanded', 'false')
    this.stopPolling()
    this.launcher.focus()
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
        await this.deps.api.send(token, text, newThread, key)
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
    this.log.append(this.bubble(m.role, m.text))
    this.log.scrollTop = this.log.scrollHeight
    return true
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
  }
}
