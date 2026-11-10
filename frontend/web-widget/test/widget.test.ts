import { describe, expect, it, beforeEach } from 'vitest'
import { failureOf, WidgetError, type WebChatApi } from '../src/api'
import { Widget, type Dependencies } from '../src/widget'
import type { ChannelConfig, MessagePage } from '../src/types'

const BRAND: ChannelConfig = {
  public_key: 'pk_ABCDEFGHIJKLMNOPQRSTUVWX',
  turnstile_site_key: 'site',
  privacy_policy_url: 'https://caucewebs.es/privacidad',
  consent_mode: 'NOTICE',
  consent_required: false,
  active: true,
  appearance: {
    name: 'Cauce', avatar_url: null, primary_color: '#0b5fff', on_primary_color: '#ffffff',
    background_color: '#ffffff', text_color: '#111827', font_family: 'Inter, sans-serif',
    greeting: 'Hola, ¿en qué te ayudo?', placeholder: null, launcher_label: null,
    unavailable_message: 'Ahora no puedo responder.', contact: 'hola@caucewebs.es', locale: 'es',
  },
}

interface Fake {
  deps: Dependencies
  calls: string[]
  pages: MessagePage[]
  timers: Array<() => void>
  delays: number[]
  failSend: WidgetError | null
  sent: Array<{ context?: unknown; opening?: unknown }>
  events: Array<Record<string, unknown>>
  openings: unknown[]
  path: string
  fine: boolean
}

function fake(): Fake {
  const f: Fake = { calls: [], pages: [], timers: [], delays: [], failSend: null, sent: [], events: [], openings: [],
    path: '/', fine: true, deps: null as unknown as Dependencies }
  const api = {
    config: async () => BRAND,
    openSession: async (_t: string, consent?: boolean, version?: string) => {
      f.calls.push(`session consent=${consent ?? false} version=${consent ? version : '-'}`)
      return { session_token: 'tok' }
    },
    send: async (token: string, content: string, newThread: boolean, _key: string,
      extra: { context?: unknown; opening?: unknown } = {}) => {
      f.calls.push(`send ${token} ${content} new=${newThread}`)
      f.sent.push(extra)
      if (f.failSend) throw f.failSend
      return { conversation_id: 'c1', message_id: 'm1', invocation_id: 'i1' }
    },
    event: async (_token: string, body: Record<string, unknown>) => { f.events.push(body) },
    opening: async (body: unknown) => { f.openings.push(body) },
    read: async () => {
      f.calls.push('read')
      return f.pages.shift() ?? { conversation_id: 'c1', data: [], next_cursor: null, awaiting_reply: false }
    },
  } as unknown as WebChatApi
  f.deps = {
    api,
    turnstile: async () => { f.calls.push('turnstile'); return 'challenge' },
    now: () => 0,
    schedule: (fn, ms) => { f.timers.push(fn); f.delays.push(ms); return f.timers.length },
    cancel: (handle) => { f.timers[(handle as number) - 1] = () => {} },
    newKey: () => 'key-1',
    random: () => 0.7,
    path: () => f.path,
    finePointer: () => f.fine,
  }
  return f
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function mount(config: ChannelConfig, f: Fake): Widget {
  const w = new Widget(config, f.deps)
  document.body.appendChild(w.host)
  return w
}

const $ = <T extends Element>(w: Widget, selector: string) => w.shadow.querySelector(selector) as T

beforeEach(() => {
  document.body.replaceChildren()
  localStorage.clear()
})

describe('the widget', () => {
  it('is a labelled launcher and a non-modal dialog that always says it is an AI, with nothing sent before a message', async () => {
    const f = fake()
    const w = mount(BRAND, f)
    const launcher = $<HTMLButtonElement>(w, '.launcher')

    expect(launcher.getAttribute('aria-expanded')).toBe('false')
    expect(launcher.getAttribute('aria-label')).toBe('Abrir el chat')
    launcher.click()
    const panel = $<HTMLElement>(w, '[role="dialog"]')
    expect(panel.hidden).toBe(false)
    expect(panel.getAttribute('aria-modal')).toBe('false')
    expect(launcher.getAttribute('aria-expanded')).toBe('true')
    expect($<HTMLElement>(w, '#cauce-ai').textContent).toContain('inteligencia artificial')
    expect($<HTMLElement>(w, '[role="log"]').getAttribute('aria-live')).toBe('polite')
    expect($<HTMLLabelElement>(w, 'label[for="cauce-input"]').textContent).toBe('Tu mensaje')
    expect(w.shadow.activeElement).toBe($(w, '#cauce-input'))
    expect($<HTMLAnchorElement>(w, '.privacy a').href).toBe('https://caucewebs.es/privacidad')
    await flush()
    expect(f.calls).toEqual([])
  })

  it('closes with Escape and gives the focus back to the launcher', () => {
    const w = mount(BRAND, fake())
    $<HTMLButtonElement>(w, '.launcher').click()
    $<HTMLElement>(w, '#cauce-input').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))

    expect($<HTMLElement>(w, '[role="dialog"]').hidden).toBe(true)
    expect(w.shadow.activeElement).toBe($(w, '.launcher'))
  })

  it('takes its look from the channel: colours and typeface as custom properties, no brand of its own', () => {
    const w = mount(BRAND, fake())
    const css = $<HTMLStyleElement>(w, 'style').textContent ?? ''

    expect(css).toContain('--c-primary:#0b5fff')
    expect(css).toContain('--font:Inter, sans-serif')
    expect($<HTMLElement>(w, 'h2').textContent).toBe('Cauce')
  })

  it('opens a session with Turnstile only at the first message, sends it, and shows the thread as text', async () => {
    const f = fake()
    f.pages.push({ conversation_id: 'c1', next_cursor: 'm2', awaiting_reply: false, data: [
      { id: 'm1', role: 'user', text: 'Hola', created_at: '' },
      { id: 'm2', role: 'agent', text: '<b>¡Hola!</b>', created_at: '' }] })
    const w = mount(BRAND, f)
    $<HTMLButtonElement>(w, '.launcher').click()
    $<HTMLTextAreaElement>(w, '#cauce-input').value = 'Hola'

    await w.submit(false)
    await flush()

    expect(f.calls.slice(0, 3)).toEqual(['turnstile', 'session consent=false version=-', 'send tok Hola new=false'])
    expect(localStorage.getItem('cauce:web:' + BRAND.public_key + ':session')).toBe('tok')
    const items = w.shadow.querySelectorAll('.msg')
    expect(items).toHaveLength(3) // the greeting, then the thread
    expect(items[2].querySelector('b')).toBeNull()
    expect(items[2].textContent).toContain('<b>¡Hola!</b>')
  })

  it('with acceptance, asks for it first, then sends it with the version of its notice text', async () => {
    const f = fake()
    const w = mount({ ...BRAND, consent_mode: 'ACCEPTANCE', consent_required: true }, f)
    $<HTMLButtonElement>(w, '.launcher').click()
    $<HTMLTextAreaElement>(w, '#cauce-input').value = 'Hola'

    await w.submit(false)
    expect(f.calls).toEqual([])
    expect($<HTMLElement>(w, '[role="alert"]').textContent).toContain('acepta')

    $<HTMLInputElement>(w, '#cauce-accept').checked = true
    await w.submit(false)
    expect(f.calls[1]).toBe('session consent=true version=es-1')
  })

  it('when the service cannot answer, shows the channel’s friendly message and contact, never a technical error', async () => {
    const f = fake()
    f.failSend = new WidgetError({ kind: 'unavailable' })
    const w = mount(BRAND, f)
    $<HTMLButtonElement>(w, '.launcher').click()
    $<HTMLTextAreaElement>(w, '#cauce-input').value = 'Hola'

    await w.submit(false)

    const alert = $<HTMLElement>(w, '[role="alert"]')
    expect(alert.hidden).toBe(false)
    expect(alert.textContent).toContain('Ahora no puedo responder.')
    expect(alert.textContent).toContain('hola@caucewebs.es')
    expect(alert.textContent).not.toMatch(/503|error|rate_limit/i)
  })

  it('a full thread offers to start over, which sends with new_thread', async () => {
    const f = fake()
    f.failSend = new WidgetError({ kind: 'thread_full' })
    const w = mount(BRAND, f)
    $<HTMLButtonElement>(w, '.launcher').click()
    $<HTMLTextAreaElement>(w, '#cauce-input').value = 'Otra'
    await w.submit(false)

    f.failSend = null
    const again = $<HTMLButtonElement>(w, '[role="alert"] button')
    expect(again.textContent).toBe('Empezar una nueva')
    again.click()
    await flush()
    expect(f.calls).toContain('send tok Otra new=true')
  })
})

describe('failureOf', () => {
  const response = (status: number, body: unknown, headers: Record<string, string> = {}) =>
    new Response(JSON.stringify(body), { status, headers })

  it('reads what the person can act on, and everything else as unavailable', async () => {
    expect(await failureOf(response(429, { error: 'rate_limited' }, { 'Retry-After': '12' })))
      .toEqual({ kind: 'rate_limited', retryAfterSeconds: 12 })
    expect(await failureOf(response(401, { error: 'invalid_session' }))).toEqual({ kind: 'session' })
    expect(await failureOf(response(409, { error: 'thread_full' }))).toEqual({ kind: 'thread_full' })
    expect(await failureOf(response(503, { error: 'rate_limit_unavailable' }))).toEqual({ kind: 'unavailable' })
    expect(await failureOf(response(409, { error: 'channel_disabled' }))).toEqual({ kind: 'unavailable' })
    expect(await failureOf(response(403, { error: 'forbidden' }))).toEqual({ kind: 'unavailable' })
  })
})

const SHOP: ChannelConfig = {
  ...BRAND,
  assistant: {
    enabled: true, bubble: '¿Te ayudo a elegir plan?', card_button_label: 'Contratar',
    routes: [{ path: '/', signals: ['comparing', 'back_to_product'] }, { path: '/pagar', signals: ['idle_cart', 'exit_intent'] }],
    tuning: { comparing_views: 3, comparing_minutes: 5, idle_cart_minutes: 3 },
    templates: { idle_cart: '¿Te ayudo a terminar el pago?', exit_intent: '¿Te vas? Te ayudo.', comparing: '¿Dudas entre planes?' },
    variants: [{ id: 'a', bubble: null, templates: null, signals: null },
      { id: 'b', bubble: '¿Hablamos?', templates: { idle_cart: '¿Algo te frena?' }, signals: null }],
    proactive_requires_consent: false, opening_counter: false, opening_counter_requires_consent: false,
  },
}

/** Runs the timer the widget scheduled with {@code ms}, the last one. */
function runTimer(f: Fake, ms: number): void {
  const at = f.delays.lastIndexOf(ms)
  expect(at).toBeGreaterThanOrEqual(0)
  f.timers[at]()
}

describe('the shop-assistant mode', () => {
  it('shows the variant\'s bubble from page load, without a request, and keeps the variant for the visitor', () => {
    const f = fake()
    const w = mount(SHOP, f)

    expect($<HTMLButtonElement>(w, '.presence .say').textContent).toBe('¿Hablamos?') // random 0.7 draws "b"
    expect(f.calls).toEqual([])
    expect(localStorage.getItem('cauce:web:pk_ABCDEFGHIJKLMNOPQRSTUVWX:variant')).toBe('b')
    $<HTMLButtonElement>(w, '.presence .say').click()
    expect($<HTMLElement>(w, '.panel').hidden).toBe(false)
    expect($<HTMLElement>(w, '.presence').hidden).toBe(true)
  })

  it('opens by itself on an idle cart at /pagar, once, with the variant\'s template, and never after the person closed it', () => {
    const f = fake()
    f.path = '/pagar'
    const w = mount(SHOP, f)
    w.setCart([{ ref: 'plan-plus', quantity: 1 }])
    runTimer(f, 3 * 60_000)

    expect($<HTMLElement>(w, '.panel').hidden).toBe(false)
    expect($<HTMLElement>(w, '.log').textContent).toContain('¿Algo te frena?')
    w.close()
    w.setCart([{ ref: 'plan-basico', quantity: 1 }])
    runTimer(f, 3 * 60_000)
    expect($<HTMLElement>(w, '.panel').hidden).toBe(true)

    // A new page load of the same browser: still closed by the person, so no proactive opening.
    const again = fake()
    again.path = '/pagar'
    const w2 = mount(SHOP, again)
    w2.setCart([{ ref: 'plan-plus', quantity: 1 }])
    runTimer(again, 3 * 60_000)
    expect($<HTMLElement>(w2, '.panel').hidden).toBe(true)
  })

  it('opens only on its route\'s signals, desktop-only exit intent, and with consent when the channel requires it', () => {
    const f = fake()
    const w = mount(SHOP, f)
    w.setCart([{ ref: 'plan-plus', quantity: 1 }]) // idle cart is not on at "/"
    runTimer(f, 3 * 60_000)
    expect($<HTMLElement>(w, '.panel').hidden).toBe(true)

    const touch = fake()
    touch.path = '/pagar'
    touch.fine = false
    const onPhone = mount(SHOP, touch)
    document.dispatchEvent(new MouseEvent('mouseout', { clientY: -1 }))
    expect($<HTMLElement>(onPhone, '.panel').hidden).toBe(true)

    localStorage.clear()
    const gated = fake()
    gated.path = '/pagar'
    const strict = { ...SHOP, assistant: { ...SHOP.assistant!, proactive_requires_consent: true } }
    const w3 = mount(strict, gated)
    w3.setCart([{ ref: 'plan-plus', quantity: 1 }])
    runTimer(gated, 3 * 60_000)
    expect($<HTMLElement>(w3, '.panel').hidden).toBe(true)
    w3.consent(true)
    w3.setCart([{ ref: 'plan-plus', quantity: 2 }])
    runTimer(gated, 3 * 60_000)
    expect($<HTMLElement>(w3, '.panel').hidden).toBe(false)
  })

  it('opens on comparing three products within five minutes, by ref', () => {
    const f = fake()
    const w = mount(SHOP, f)
    w.viewProduct('plan-basico')
    w.viewProduct('plan-plus')
    expect($<HTMLElement>(w, '.panel').hidden).toBe(true)
    w.viewProduct('plan-tienda')
    expect($<HTMLElement>(w, '.panel').hidden).toBe(false)
    expect($<HTMLElement>(w, '.log').textContent).toContain('¿Dudas entre planes?')
  })

  it('sends where the person is and how the chat opened with the first message after the opening, only', async () => {
    const f = fake()
    f.path = '/pagar'
    const w = mount(SHOP, f)
    w.viewProduct('plan-plus')
    w.setCart([{ ref: 'plan-plus', quantity: 1 }])
    runTimer(f, 3 * 60_000)
    $<HTMLTextAreaElement>(w, '#cauce-input').value = '¿Qué incluye?'
    await w.submit(false)
    $<HTMLTextAreaElement>(w, '#cauce-input').value = 'Vale'
    await w.submit(false)

    expect(f.sent[0]).toEqual({
      context: { path: '/pagar', product_ref: 'plan-plus', cart: [{ ref: 'plan-plus', quantity: 1 }] },
      opening: { kind: 'proactive', signal: 'idle_cart', variant: 'b' },
    })
    expect(f.sent[1].opening).toBeUndefined()
    expect(f.sent[1].context).toEqual(f.sent[0].context)
  })

  it('shows a reply\'s cards from the catalog, whose button calls the shop\'s hook and is reported', async () => {
    const f = fake()
    const w = mount(SHOP, f)
    const added: string[] = []
    w.onAddToCart((ref, quantity) => added.push(`${ref}x${quantity}`))
    $<HTMLButtonElement>(w, '.launcher').click()
    $<HTMLTextAreaElement>(w, '#cauce-input').value = '¿Qué plan?'
    await w.submit(false)
    f.pages.push({ conversation_id: 'c1', next_cursor: 'm2', awaiting_reply: false, data: [
      { id: 'm2', role: 'agent', text: 'Te encaja el Plus.', created_at: '2026-11-10T10:00:00Z',
        cards: [{ ref: 'plan-plus', name: 'Plan Plus', description: 'Para cambios frecuentes.', price: 79 },
          { ref: 'plan-tienda', name: 'Plan Tienda', description: null, price: 24.99 }] }] })
    f.timers[f.timers.length - 1]()
    await flush()

    const cards = w.shadow.querySelectorAll('.card')
    expect(cards).toHaveLength(2)
    expect(cards[0].textContent).toContain('Plan Plus')
    expect(cards[0].querySelector('.price')!.textContent!.replace(/\s/g, ' ')).toBe('79 €')
    expect(cards[1].querySelector('.price')!.textContent!.replace(/\s/g, ' ')).toBe('24,99 €')
    const buy = cards[0].querySelector('button')!
    expect(buy.textContent).toBe('Contratar')
    expect(buy.getAttribute('aria-label')).toBe('Contratar: Plan Plus')
    buy.click()
    expect(added).toEqual(['plan-plusx1'])
    expect(f.events).toEqual([{ type: 'add_to_cart', ref: 'plan-plus', quantity: 1, variant: 'b' }])
  })

  it('reports a purchase only once this browser has talked to the chat, and counts openings only when the channel says', async () => {
    const f = fake()
    const counted = { ...SHOP, assistant: { ...SHOP.assistant!, opening_counter: true } }
    const w = mount(counted, f)
    w.trackPurchase({ orderId: 'CW-1', value: 79 })
    expect(f.events).toEqual([])

    $<HTMLButtonElement>(w, '.launcher').click()
    expect(f.openings).toEqual([{ kind: 'on_demand', variant: 'b' }])
    $<HTMLTextAreaElement>(w, '#cauce-input').value = 'Hola'
    await w.submit(false)
    w.trackPurchase({ orderId: 'CW-2', value: 95.59 })
    expect(f.events).toEqual([{ type: 'purchase', order_id: 'CW-2', value: 95.59, variant: 'b' }])
  })

  it('without the mode, sends no context and shows no bubble', async () => {
    const f = fake()
    const w = mount(BRAND, f)
    expect($<HTMLElement>(w, '.presence')).toBeNull()
    $<HTMLButtonElement>(w, '.launcher').click()
    $<HTMLTextAreaElement>(w, '#cauce-input').value = 'Hola'
    await w.submit(false)
    expect(f.sent[0]).toEqual({})
  })
})

