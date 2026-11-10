// Gets a Cloudflare Turnstile token when a session must open (ADR 0014, WC2), never before the person's
// first message: the script is loaded only then. Turnstile renders in the page's own DOM, in a container
// the widget adds; it stays out of sight unless Cloudflare needs the person to interact.

interface TurnstileApi {
  render(container: HTMLElement, options: Record<string, unknown>): string
  remove(widgetId: string): void
}

declare global {
  interface Window {
    turnstile?: TurnstileApi
  }
}

const SCRIPT = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'
let loading: Promise<TurnstileApi> | null = null

function loadTurnstile(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile)
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = SCRIPT
      script.async = true
      script.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error('turnstile')))
      script.onerror = () => {
        loading = null
        reject(new Error('turnstile'))
      }
      document.head.appendChild(script)
    })
  }
  return loading
}

/** A fresh Turnstile token for {@code siteKey}; rejects when Turnstile cannot be reached. */
export async function turnstileToken(siteKey: string, locale: string): Promise<string> {
  const api = await loadTurnstile()
  const host = document.createElement('div')
  host.setAttribute('data-cauce-turnstile', '')
  host.style.cssText = 'position:fixed;bottom:88px;right:16px;z-index:2147483647'
  document.body.appendChild(host)
  try {
    return await new Promise<string>((resolve, reject) => {
      api.render(host, {
        sitekey: siteKey,
        appearance: 'interaction-only',
        language: locale,
        callback: (token: string) => resolve(token),
        'error-callback': () => reject(new Error('turnstile')),
        'expired-callback': () => reject(new Error('turnstile')),
      })
    })
  } finally {
    host.remove()
  }
}
