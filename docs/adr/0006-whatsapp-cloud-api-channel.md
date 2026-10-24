# ADR 0006: The WhatsApp Cloud API channel

Status: Accepted and implemented; the first real conversation confirms the `recipient` field.

## Context

WhatsApp is the channel the SPI was designed against (ADR 0004 cites it as the forcing case of
typed identity), and the first one whose provider model differs from the instance-per-URL shape
the Telegram adapter fits:

- **Identity.** Since April 2026 every message webhook carries a business-scoped user id (BSUID:
  `from_user_id` / `user_id`, e.g. `US.13491208655302741918`), scoped to the Meta business
  portfolio. When a user adopts a username, the phone number (`from`, `wa_id`) can be absent.
  The BSUID is regenerated when the user changes number, announced by a `system` message
  carrying `previous_user_id`. Sending to a BSUID is supported since July 2026.
- **Webhooks.** Meta configures one callback URL per *app*, not per phone number. Subscription is
  verified once with a GET (`hub.mode`, `hub.verify_token`, `hub.challenge`); every event POST is
  signed with `X-Hub-Signature-256: sha256=<hex>`, an HMAC-SHA256 of the raw body under the app
  secret. Events for every number of the app arrive at that URL, distinguished by
  `metadata.phone_number_id`.
- **Secrets.** Verifying the signature needs the app secret itself, not a hash of it; sending
  needs an access token. Channel credentials are stored in plaintext today (deferred work).
- **The 24-hour window.** A free-form message can be sent only within 24 hours of the user's
  last message; outside it, only templates approved by Meta.

## Decision

**W1 — the thread is keyed by the BSUID.** The interlocutor is the BSUID (`user_id` /
`from_user_id`) as `PROVIDER_USER_ID`. The phone number is not stored; replies are sent to the
BSUID. When the BSUID changes (the change-of-number `system` webhook), the new BSUID is a new
identity and the next message opens a new thread, with a WARN; history is not migrated.
Conscious limitation: joining the two identities is the memory subject ADR 0004 defers. The
parent BSUID (`parent_user_id`, across portfolios) is out of scope.

**W2 — verify, then route.** The `X-Hub-Signature-256` signature is verified with the app
secret **before** anything in the request is read or routed. Only then is the event routed by
`metadata.phone_number_id` — unique across the instance, enforced by an index — to its channel
config.

**W3 — encryption at rest, now.** The app secret and the access token are stored encrypted with
AES-GCM under a key held outside the database (an environment variable). Key rotation is
deferred work.

**W4 — outside the 24-hour window, a loud failure.** A reply or a fallback that would fall
outside the window is not sent; the reason is logged, consistent with ADR 0004's "no fit means a
loud failure". Templates are a later unit.

**W5 — text only, never silence.** Inbound text is ingested. Any other inbound message type
(image, audio, document, location…) is acknowledged with a 2xx, recorded as an event, and
answered with a fixed text telling the user that, for now, only text is read. Never silence.

### W2 in detail — the WhatsApp app record

The app secret must be found from the URL alone, and one Meta app may serve numbers bound to
agents of several tenants. So the app is an entity of its own, following the hierarchical-RLS
entity pattern:

- **A WhatsApp app is owned by a tenant** (operator, partner or client) and holds the Meta app
  id, the app secret sealed (W3), and the hash of a server-generated verify token, returned once.
- **Its webhook URL is `/webhooks/whatsapp/{appId}`.** The subscription GET and every signed
  POST arrive there. The app is resolved from the path through a `SECURITY DEFINER` function
  (the webhook carries no tenant, ADR 0001), the signature is verified, and only then is the body
  read.
- **A number is a `channel_config`** of channel `whatsapp` with the app's id and its
  `phone_number_id`, unique across the instance; its access token is the config's credential,
  sealed. A number may be bound only to an app whose tenant is the number's tenant or an
  ancestor of it: a partner's app serves its clients. Routing resolves `(app, phone_number_id)`
  to the config through a second `SECURITY DEFINER` function; a number bound to another app is
  not routed.
- **Inbound is app-routed, so it does not fit the per-config `InboundChannelAdapter`**, whose
  shape is one URL, one config, one message. The WhatsApp inbound flow is its own service in
  cauce-channels, which still ends in the same channel-agnostic ingest. The outbound half
  implements the ordinary `OutboundChannelAdapter`.

**Explicit debt: app routing belongs in the SPI.** The WhatsApp inbound service living outside
`InboundChannelAdapter` is accepted for one channel only. When a second Meta channel arrives
(Messenger, Instagram — same app, same signature, same envelope), the inbound SPI gains app-level
routing — resolve the app from the URL, verify with its secret, route each event to a config —
and both channels implement it, instead of a second app-routed service being written beside this
one.

### Design note — anything proactive is designed against the 24-hour window

Development of the agent continues on Telegram, which has no reply window; WhatsApp is complete
and parked until go-to-market. Any proactive capability — reminders, follow-ups, a message the
agent sends without having just been written to — is designed from the start under WhatsApp's
constraint, even while it is only exercised on Telegram: outside 24 hours since the
interlocutor's last message, only an approved template may be sent. So such a feature names, at
design time, what it does outside the window (a template, or not sending and recording why); it
never assumes the free-form delivery Telegram allows.

### W3 adjustment

Tolerating plaintext rows is a read path only: every write is sealed. Removing that read path
once no plaintext row can remain is registered as deferred work.

### W5 in detail

The fixed text is a platform setting. Reusing C3's per-agent override would mean a second
per-agent column and its write path, not a reuse, so a per-agent override is left out and noted.
The text is sent directly through the outbound adapter (the message was not ingested, so it opens
no thread) and the event is audited as `conduct.message.unsupported`: the message type and
ids, no content.

Since 2026-10-24 Telegram follows the same rule: a private message without text (a voice note, a
photo) is answered with `cauce.channels.telegram.unsupported-message-reply` and audited the same
way, once per `update_id`. Until then it was acknowledged in silence.

### Sending to a BSUID

A reply is sent with `recipient_type: "individual"` and `recipient: "<BSUID>"`, without `to`.
Third-party documentation disagrees on whether `to` also accepts a BSUID; the first real
conversation confirms the field.

## References

- [Meta: Business-scoped user IDs](https://developers.facebook.com/documentation/business-messaging/whatsapp/business-scoped-user-ids/)
- [Discussion of `recipient` vs `to` for BSUID sends](https://github.com/omnisolo-llc/onehumancorp/issues/36354)
- `docs/adr/0001-rls-escape-hatches.md` — tenant discovery for a webhook with no tenant context
- `docs/adr/0004-identity-message-parts-and-channel-capabilities.md` — typed identity, loud failure, the deferred memory subject
- `docs/adr/0005-agentic-loop-hardening.md` — the fallback reply W4 may block
