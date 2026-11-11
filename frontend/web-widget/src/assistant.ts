import * as storage from './storage'
import type { Assistant, CartLine, Signal, Variant } from './types'

// The shop-assistant mode in the browser (ADR 0014, WC8): which variant this visitor sees, which signals
// are on for the page, and when one fires. Everything here stays in the browser: before the person
// writes, nothing about them is sent (the anonymous opening counter, when the channel turns it on, sends
// only how the chat opened and the variant). What it keeps, in the shop page's localStorage under
// `cauce:web:{publicKey}:…`: always only the marks `proactive` (the chat already opened by itself) and
// `closed` (the person closed it); and, once the page has passed the visitor's consent, also `variant`
// and `views` (the products seen in the last hour, by ref). Without that consent those two live in this
// page's memory and are gone with it, so the signals that span pages see one page only.

export interface AssistantDependencies {
  now: () => number
  schedule: (fn: () => void, ms: number) => unknown
  cancel: (handle: unknown) => void
  random: () => number
  path: () => string
  finePointer: () => boolean
}

interface View {
  ref: string
  at: number
}

const HOUR = 60 * 60_000

export class ShopAssistant {
  readonly variant: Variant | null
  private consented = false
  private seen: View[]
  private cart = ''
  private idleHandle: unknown = null
  private onSignal: (signal: Signal, greeting: string) => void = () => {}

  constructor(readonly config: Assistant, private readonly key: string, private readonly deps: AssistantDependencies) {
    this.seen = this.storedViews()
    this.variant = this.drawVariant()
  }

  /** Called when a signal opens the chat, with the greeting to show. */
  whenSignalled(handler: (signal: Signal, greeting: string) => void): void {
    this.onSignal = handler
  }

  /** The bubble's phrase: the variant's, else the channel's. */
  bubble(): string | null {
    return this.variant?.bubble ?? this.config.bubble
  }

  variantId(): string | undefined {
    return this.variant?.id
  }

  /**
   * The shop's consent banner said the visitor consented: what was kept in memory is stored from now on.
   * Withdrawn, the variant and the views leave the browser's storage; the opened and closed marks stay.
   */
  consent(granted: boolean): void {
    this.consented = granted
    if (granted) {
      if (this.variant) storage.save(this.key, 'variant', this.variant.id)
      this.storeViews()
    } else {
      storage.forget(this.key, 'variant')
      storage.forget(this.key, 'views')
    }
  }

  /** Whether a purchase the shop confirms may be reported: the channel attributes them, and the visitor consented. */
  mayReportPurchases(): boolean {
    return this.config.purchase_attribution && this.consented
  }

  /** Whether the anonymous opening counter may count an opening now. */
  mayCountOpenings(): boolean {
    return this.config.opening_counter && (!this.config.opening_counter_requires_consent || this.consented)
  }

  /** The person closed the chat: it never opens by itself again. */
  closedByPerson(): void {
    storage.save(this.key, 'closed', '1')
  }

  /** The chat was opened, by the person or by a signal: no signal opens it again while it is open. */
  opened(): void {
    this.cancelIdle()
  }

  /** The shop's page shows the product {@code ref}: a second view, or enough different ones, may signal. */
  viewProduct(ref: string): void {
    const now = this.deps.now()
    const views = this.seen.filter((v) => now - v.at < HOUR)
    const seenBefore = views.some((v) => v.ref === ref)
    views.push({ ref, at: now })
    this.seen = views.slice(-20)
    this.storeViews()
    if (seenBefore) this.fire('back_to_product')
    const window = this.config.tuning.comparing_minutes * 60_000
    const distinct = new Set(views.filter((v) => now - v.at <= window).map((v) => v.ref))
    if (distinct.size >= this.config.tuning.comparing_views) this.fire('comparing')
  }

  /** The shop's cart as its page reports it: one left unchanged long enough is an idle cart. */
  setCart(lines: CartLine[]): void {
    const state = JSON.stringify(lines)
    if (state === this.cart) return
    this.cart = state
    this.cancelIdle()
    if (lines.length === 0) return
    this.idleHandle = this.deps.schedule(() => {
      this.idleHandle = null
      this.fire('idle_cart')
    }, this.config.tuning.idle_cart_minutes * 60_000)
  }

  /** The pointer left through the top of the viewport. Desktop only: a touch screen has no such gesture. */
  exitIntent(): void {
    if (this.deps.finePointer()) this.fire('exit_intent')
  }

  /** The signals on for the page: its route's, narrowed by the variant. */
  signalsHere(): Signal[] {
    const path = this.deps.path()
    const matching = this.config.routes.filter((r) => r.path === '/' || path === r.path
      || path.startsWith(r.path.endsWith('/') ? r.path : r.path + '/'))
    if (matching.length === 0) return []
    const route = matching.reduce((a, b) => (b.path.length > a.path.length ? b : a))
    const narrowed = this.variant?.signals
    return narrowed ? route.signals.filter((s) => narrowed.includes(s)) : route.signals
  }

  private fire(signal: Signal): void {
    if (!this.config.proactive_opening) return // the bubble alone: the shop has not turned opening on
    if (!this.signalsHere().includes(signal)) return
    if (storage.load(this.key, 'proactive') || storage.load(this.key, 'closed')) return
    if (this.config.proactive_requires_consent && !this.consented) return
    const greeting = this.variant?.templates?.[signal] ?? this.config.templates[signal]
    if (!greeting) return // no template, no greeting: the chat does not open by itself on this signal
    storage.save(this.key, 'proactive', '1')
    this.onSignal(signal, greeting)
  }

  private cancelIdle(): void {
    if (this.idleHandle !== null) this.deps.cancel(this.idleHandle)
    this.idleHandle = null
  }

  private storeViews(): void {
    if (this.consented) storage.save(this.key, 'views', JSON.stringify(this.seen))
  }

  /** Views stored on an earlier page, which only a consent let the widget store. */
  private storedViews(): View[] {
    try {
      const parsed = JSON.parse(storage.load(this.key, 'views') ?? '[]')
      return Array.isArray(parsed) ? parsed.filter((v) => typeof v?.ref === 'string' && typeof v?.at === 'number') : []
    } catch {
      return []
    }
  }

  /**
   * The visitor's variant, drawn once at random; none when the channel declares none. Kept across pages
   * only once the visitor consented: until then it is this page's.
   */
  private drawVariant(): Variant | null {
    const variants = this.config.variants
    if (variants.length === 0) return null
    const kept = variants.find((v) => v.id === storage.load(this.key, 'variant'))
    if (kept) return kept
    const drawn = variants[Math.min(variants.length - 1, Math.floor(this.deps.random() * variants.length))]
    if (this.consented) storage.save(this.key, 'variant', drawn.id)
    return drawn
  }
}
