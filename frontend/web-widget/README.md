# Cauce web widget

The web chat a shop embeds in its pages (ADR 0014, W4): one script, no framework, in its own Shadow DOM,
so neither the page's styles nor its scripts reach inside. It has **no look of its own**. Name, avatar,
colours, typeface and texts all come from the channel's configuration (`appearance`, validated by the API
at WCAG AA contrast), so each shop's chat wears that shop's brand.

## Embedding it

```html
<script src="https://<where cauce-widget.js is served>/cauce-widget.js"
        data-cauce-key="pk_…" data-cauce-instance="https://<cauce instance>" defer></script>
```

- `data-cauce-key` is the channel's public key (`POST /v1/agents/{id}/web-chat-channels`). It is not a
  secret, and no API key ever goes in a page.
- `data-cauce-instance` is the Cauce instance's URL. Changing only this attribute moves the same page from
  a tunnel to Cauce OS's own server. Without it, the script's own origin is the instance.
- The page's origin must be one of the channel's `allowed_origins`.

## What it does, and what it never does

- **Before the person writes, nothing about them leaves the browser.** The only request is the channel's
  anonymous configuration. A channel switched off (`active: false`) or unreachable shows nothing.
- **The first message** loads Cloudflare Turnstile, opens a session and sends. The session token is kept
  in the shop page's `localStorage` (ADR 0014, WC2). With explicit acceptance, the person ticks the
  privacy box first, and the widget sends the version of its notice text (`es-1`, `en-1`).
- **Replies arrive by polling**: every 1.5 s while one is coming, every 10 s after, and not at all after
  2 minutes without activity.
- **It always says it is an AI**, under the name in the header. That text and the privacy line are the
  widget's own, by locale, not settings.
- **When the service cannot answer** (Redis or Turnstile down, the channel switched off, a 5xx), it shows
  the channel's `unavailable_message` and `contact`, never a technical error. Too many messages shows how
  long to wait. A full conversation offers to start a new one.
- Replies are shown as text, never as markup.

## Accessibility

- A labelled launcher with `aria-expanded`, and a non-modal dialog labelled by its title and described by
  the AI notice.
- The conversation is an `aria-live="polite"` log. Each message carries a hidden speaker name for screen
  readers.
- Keyboard: Enter sends, Shift+Enter adds a line, Escape closes and returns the focus to the launcher.
  The focus ring is always visible.
- Touch targets are at least 44 px, and the text field is 16 px so phones do not zoom.
- On a phone (≤ 480 px wide) the dialog takes the whole screen, respecting the safe areas.
- Motion is off under `prefers-reduced-motion`.
- Contrast is held at AA by the API, which refuses a colour pair below it.

## Building and trying it

```bash
npm install
npm run build      # type-check, tests (Vitest, jsdom), bundle, and the weight against its 20 KB gzip budget
npm run dev        # http://localhost:5174/demo/index.html?key=pk_…&instance=http://localhost:8080
```

`demo/mobile.html` shows the same page at a phone's width (390 × 844). For a local end-to-end try,
create a web chat whose `allowed_origins` include `http://localhost:5174`, and use Cloudflare's test keys
for Turnstile (site key `1x00000000000000000000AA`, secret `1x0000000000000000000000000000000AA`, both
always pass).

Weight on 2026-11-10: 15.3 KB raw, **5.7 KB gzip**, 4.9 KB brotli.
