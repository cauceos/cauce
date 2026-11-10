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
  failSend: WidgetError | null
}

function fake(): Fake {
  const f: Fake = { calls: [], pages: [], timers: [], failSend: null, deps: null as unknown as Dependencies }
  const api = {
    config: async () => BRAND,
    openSession: async (_t: string, consent?: boolean, version?: string) => {
      f.calls.push(`session consent=${consent ?? false} version=${consent ? version : '-'}`)
      return { session_token: 'tok' }
    },
    send: async (token: string, content: string, newThread: boolean) => {
      f.calls.push(`send ${token} ${content} new=${newThread}`)
      if (f.failSend) throw f.failSend
      return { conversation_id: 'c1', message_id: 'm1', invocation_id: 'i1' }
    },
    read: async () => {
      f.calls.push('read')
      return f.pages.shift() ?? { conversation_id: 'c1', data: [], next_cursor: null, awaiting_reply: false }
    },
  } as unknown as WebChatApi
  f.deps = {
    api,
    turnstile: async () => { f.calls.push('turnstile'); return 'challenge' },
    now: () => 0,
    schedule: (fn) => { f.timers.push(fn); return f.timers.length },
    cancel: () => {},
    newKey: () => 'key-1',
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
