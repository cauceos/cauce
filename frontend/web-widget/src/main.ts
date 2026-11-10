import { WebChatApi } from './api'
import { Widget, defaultDependencies } from './widget'

// The embed (ADR 0014, W4): one script tag, nothing else on the page.
//
//   <script src="https://<where the file is served>/cauce-widget.js" data-cauce-key="pk_…"
//           data-cauce-instance="https://<instance>" defer></script>
//
// data-cauce-instance lets the same page move from a tunnel to Cauce OS's own server; without it, the
// script's own origin is the instance. The one request before the person writes is the channel's
// anonymous configuration; an inactive or unreachable channel shows nothing at all.
//
// The shop's page talks to the widget with `cauce(command, …)` (WC8). Calls made before the script has
// loaded wait in `window.cauceq`, so the page may call it at any time:
//
//   <script>window.cauceq=window.cauceq||[];function cauce(){cauceq.push(arguments)}</script>
//   cauce('view', 'plan-plus')                       // the product on screen, by catalog ref
//   cauce('cart', [{ref: 'plan-plus', quantity: 1}]) // the cart, by catalog ref
//   cauce('onAddToCart', (ref, quantity) => …)       // what a card's button does
//   cauce('purchase', {orderId: 'CW-1', value: 95.59}) // a purchase, once the payment is confirmed
//   cauce('consent', true)                           // the consent banner's answer
//   cauce('open')                                    // open the chat for the person

type Command = [string, ...unknown[]]

declare global {
  interface Window {
    cauceq?: ArrayLike<unknown>[]
    cauce?: (...command: unknown[]) => void
  }
}

function run(widget: Widget, [name, arg]: Command): void {
  switch (name) {
    case 'view':
      if (typeof arg === 'string') widget.viewProduct(arg)
      return
    case 'cart':
      if (Array.isArray(arg)) widget.setCart(arg)
      return
    case 'onAddToCart':
      if (typeof arg === 'function') widget.onAddToCart(arg as (ref: string, quantity: number) => void)
      return
    case 'purchase':
      widget.trackPurchase(arg as { orderId: string; value: number })
      return
    case 'consent':
      widget.consent(arg === true)
      return
    case 'open':
      widget.open()
      return
    default:
      // an unknown command does nothing: a page written for a newer widget keeps working
  }
}

async function boot(script: HTMLScriptElement | null): Promise<void> {
  const key = script?.dataset.cauceKey
  if (!key) return
  // Until the widget exists, commands wait; after, they run at once.
  const waiting: Command[] = Array.from(window.cauceq ?? [], (args) => Array.from(args) as Command)
  let widget: Widget | null = null
  window.cauce = (...command: unknown[]) => {
    if (widget) run(widget, command as Command)
    else waiting.push(command as Command)
  }
  const instance = script?.dataset.cauceInstance ?? new URL(script!.src, window.location.href).origin
  const api = new WebChatApi(instance, key)
  let config
  try {
    config = await api.config()
  } catch {
    return
  }
  if (!config.active) return
  widget = new Widget(config, defaultDependencies(api))
  const ready = widget
  const mount = () => document.body.appendChild(ready.host)
  if (document.body) mount()
  else document.addEventListener('DOMContentLoaded', mount, { once: true })
  for (const command of waiting.splice(0)) run(ready, command)
}

void boot(document.currentScript as HTMLScriptElement | null)
