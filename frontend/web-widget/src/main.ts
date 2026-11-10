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

async function boot(script: HTMLScriptElement | null): Promise<void> {
  const key = script?.dataset.cauceKey
  if (!key) return
  const instance = script?.dataset.cauceInstance ?? new URL(script!.src, window.location.href).origin
  const api = new WebChatApi(instance, key)
  let config
  try {
    config = await api.config()
  } catch {
    return
  }
  if (!config.active) return
  const widget = new Widget(config, defaultDependencies(api))
  const mount = () => document.body.appendChild(widget.host)
  if (document.body) mount()
  else document.addEventListener('DOMContentLoaded', mount, { once: true })
}

void boot(document.currentScript as HTMLScriptElement | null)
