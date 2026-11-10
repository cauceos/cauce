# Cauce web widget

The web chat a shop embeds in its pages (ADR 0014, W4): one script, no framework, in its own Shadow DOM,
so neither the page's styles nor its scripts reach inside. It has **no look of its own**. Name, avatar,
colours, typeface and texts all come from the channel's configuration (`appearance`, validated by the API
at WCAG AA contrast), so each shop's chat wears that shop's brand.

## Embedding it

The Cauce instance serves each release at a versioned path, cached for a year as immutable. The tag pins
the version and its Subresource Integrity hash, so the browser refuses a file that is not exactly the
released one. `npm run build` writes it in `dist/release/{version}/embed.html`:

```html
<script src="https://<cauce instance>/web/widget/0.2.0/cauce-widget.js"
        integrity="sha384-…" crossorigin="anonymous"
        data-cauce-key="pk_…" data-cauce-instance="https://<cauce instance>" defer></script>
```

To publish a release, copy `dist/release/{version}/` into the instance's `cauce.web.widget-dir`
(`WEB_WIDGET_DIR` in production). A new version is a new directory, and old tags keep working. Change
`version` in `package.json` for every change to the widget: a path's file must never change.

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

## The shop-assistant mode (ADR 0014, WC8)

When the channel turns it on (`PUT /v1/web-chat-channels/{id}/assistant`), the widget behaves like a shop
assistant who is present without getting in the way:

- **A presence bubble** beside the launcher from page load, with the channel's phrase (or the variant's).
  It is static, from the configuration: no model call, no request. It can be dismissed.
- **It opens by itself only on a signal** that the browser computes, on a route where that signal is on
  (the longest path prefix of the configuration wins), greeting with the signal's template:

  | Signal | When |
  |---|---|
  | `back_to_product` | The same product is viewed a second time |
  | `comparing` | Three or more products within 5 minutes (tunable) |
  | `idle_cart` | A cart the page reported non-empty, unchanged for 3 minutes (tunable) |
  | `exit_intent` | The pointer leaves through the top of the window, desktop only |

  At most once per browser, never again after the person has closed the chat, and, when the channel's
  `proactive_requires_consent` is on, only after the page has passed the visitor's consent.
- **A variant** (up to five per channel) is drawn at random and kept for the browser; the metrics count by
  variant.
- **With each message**, the page's path, the product on screen and the cart go along, by catalog ref, and
  with the first message after an opening, how the chat was opened. The API keeps only the path, without
  its query, and only the refs the catalog holds.
- **Product cards** under a reply show the item's name, price and description as the catalog has them,
  and a button whose action is the shop's own hook. Without a hook, a card shows no button.

The page tells the widget what it knows with `cauce(command, …)`. Calls made before the script loads wait
in `window.cauceq`, so this line can go before the tag:

```html
<script>window.cauceq=window.cauceq||[];function cauce(){cauceq.push(arguments)}</script>
<script>
  cauce('view', 'plan-plus')                          // the product on screen, by catalog ref
  cauce('cart', [{ref: 'plan-plus', quantity: 1}])    // the cart, by catalog ref ([] when empty)
  cauce('onAddToCart', (ref, quantity) => {           // what a card's button does
    location.href = '/pagar?plan=' + encodeURIComponent(ref)
  })
  cauce('purchase', {orderId: 'CW-1042', value: 95.59}) // once the payment is confirmed
  cauce('consent', true)                              // the consent banner's answer, when it matters
  cauce('open')                                       // open the chat on the person's behalf
</script>
```

A purchase is reported only when this browser has talked to the chat; Cauce attributes it when it comes
within 7 days of the conversation's last message.

**What it keeps in the shop page's `localStorage`**, under `cauce:web:{public key}:`:

| Key | What |
|---|---|
| `session` | The session token, once the person has written (WC2) |
| `variant` | The variant drawn for this browser |
| `proactive` | The chat already opened by itself once |
| `closed` | The person closed the chat: it never opens by itself again |
| `views` | The products viewed in the last hour, by ref, for `back_to_product` and `comparing` |

None of them leaves the browser before the person writes. With the channel's `opening_counter` on (off
by default), each opening sends only how it opened and the variant, with no session or identifier, and,
with `opening_counter_requires_consent`, only after the page has passed the consent.

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

## Checking it on a phone and with a screen reader

Before publishing on a real page (the hidden test page first), go through this on a physical phone:

**Phone (iOS Safari and Android Chrome)**
- [ ] The launcher sits in the bottom corner and covers nothing important, including the iOS home bar.
- [ ] Opening it fills the screen. The header, the AI notice and the field are visible with the keyboard
      up, and the field is not hidden behind the keyboard.
- [ ] Tapping the field does not zoom the page.
- [ ] Sending from the keyboard's send key and from the button both work. A long reply scrolls inside the
      chat, not the page behind it.
- [ ] Rotating the phone keeps the chat usable.
- [ ] The privacy link opens in a new tab and coming back keeps the conversation.
- [ ] Closing the chat and reloading the page keeps the conversation (same session).
- [ ] With the connection off, sending shows the friendly message and the contact, never an error code.
- [ ] With the shop-assistant mode: the presence bubble covers neither the page's content nor its own
      launcher, and its × hides it. On `/pagar`, leaving a plan chosen for 3 minutes opens the chat once
      with the greeting; after closing it, it does not open by itself again, even on reload.
- [ ] A reply with plan cards shows each card's name and price; its button takes you to
      `/pagar?plan=…`, and its touch target is at least 44 px.

**Screen reader (VoiceOver on iOS, TalkBack on Android, NVDA or VoiceOver on desktop)**
- [ ] The launcher is announced as a button with its label and as collapsed/expanded.
- [ ] Opening it announces the dialog by its name, and the AI notice is read.
- [ ] The field is announced as "Tu mensaje" (or "Your message").
- [ ] A reply is announced when it arrives without moving the focus, with who says it.
- [ ] The friendly message when the service cannot answer is announced.
- [ ] The privacy link (or the acceptance box) is reachable and announced.
- [ ] Escape on desktop closes the chat and the focus returns to the launcher.
- [ ] The presence bubble is a button announced with its phrase, and its × as "Cerrar el aviso".
- [ ] When the chat opens by itself, the greeting is read, and the focus is in the field.
- [ ] Each card is announced as an item of the list "Productos", and its button as "Contratar: Plan …".

**Keyboard only (desktop)**
- [ ] Tab reaches the launcher, then inside the chat the close button, the privacy link, the field and
      Send, with a visible focus ring on each.

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

Weight of 0.2.0 (2026-11-10, with the shop-assistant mode): 22.1 KB raw, **7.8 KB gzip**, 6.8 KB brotli.
0.1.0 was 15.3 KB raw and 5.7 KB gzip.
