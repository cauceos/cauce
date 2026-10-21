# ADR 0006: The WhatsApp Cloud API channel

Status: Accepted in part (W1–W5 decided; the app record of W2 pending). Not yet implemented.

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

## Pending

**Where the app secret lives, and who owns it.** W2 verifies with the app secret before
routing, so the secret must be found from the request URL alone, and one Meta app may serve
numbers bound to agents of several tenants (a partner's app for its clients). This needs an
app-level record, identified by the webhook URL, distinct from the per-number channel config.
Its ownership and tenancy are not decided yet.

## References

- [Meta: Business-scoped user IDs](https://developers.facebook.com/documentation/business-messaging/whatsapp/business-scoped-user-ids/)
- `docs/adr/0001-rls-escape-hatches.md` — tenant discovery for a webhook with no tenant context
- `docs/adr/0004-identity-message-parts-and-channel-capabilities.md` — typed identity, loud failure, the deferred memory subject
- `docs/adr/0005-agentic-loop-hardening.md` — the fallback reply W4 may block
