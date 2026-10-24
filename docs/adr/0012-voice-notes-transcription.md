# ADR 0012: Voice notes, transcribed behind a neutral port

Status: Accepted. V1's audio part (unit 2b of ADR 0004) and transcription (2c) implemented; the
channels (2d) not yet. V5 amended on 2026-10-24 (below).

## Context

People answer a business on Telegram and WhatsApp with voice notes as often as with text. Until
2026-10-24 Cauce met a Telegram voice note with silence (the update was acknowledged and dropped);
WhatsApp answered it with the fixed text-only reply (ADR 0006, W5), and since that date Telegram
does too. Neither reads it.

Reading it needs three things the system does not have:

- **The models do not take audio.** The agents' providers accept text (and, some, images and
  documents), not audio. A voice note reaches the model as its transcript, so transcription is a
  capability of its own, ahead of the model call.
- **A place in the message for something that is not text.** ADR 0004 (decision 2) makes a
  message a list of parts with a binary reference among its types, and leaves "audio" to arrive
  with the adapter that produces it. This is that arrival.
- **Accounting and limits for it.** ADR 0010 knows LLM tokens only, and nothing bounds the size of
  what a channel can hand over.

## Decision

### V1 — The audio part carries its transcript; the bytes are never stored

A voice note is one **audio part**: a binary reference (media type, duration, size, SHA-256 of the
bytes, and the provider's opaque reference to them — Telegram's `file_id`, WhatsApp's media id)
plus its **transcript**, inside the same part. The transcript is derived from that audio and has
no meaning without it: erasing the part erases both.

The bytes are downloaded, transcribed in memory and dropped. They never enter the database or any
store of Cauce's. The cost is accepted: once the provider no longer holds the media, a voice note
cannot be transcribed again.

**The transcript is message text for personal data.** It lives in the mutable business row, where
deletion operates, and never in the audit chain — the audit sink's rule (CLAUDE.md, design rules).

### V2 — Transcription runs in the worker, and is audited by hash

The webhook only ingests: it stores the message with its audio part and no transcript yet, and
acknowledges the provider at once. The worker transcribes as the **first step of the invocation**,
before the context is built, so retries, backoff and the fallback reply (ADR 0005, C3) apply to it
as to any step. A transcription that fails ends the invocation with its own failure type and the
person gets the fallback reply, never silence.

The transcript is written into the message's audio part and audited as
`conduct.message.transcribed`: the message and invocation ids, the duration, and the **hash** of
the transcript (`cauce-content:v2`, ADR 0004), never the text. `conduct.message.received` keeps the
hash of the parts as they were received: an audio part by its media type and provider reference
only (the content hash v2, `docs/spec/audit-chain-format.md` §6.3), so that filling in the
transcript, the bytes' hash, size and duration never moves the hash the message was audited
under.

### V3 — A neutral port; the first adapter is a local Whisper

Transcription is a port of its own, provider-neutral like the LLM SPI (invariant 3): audio in,
text and the audio's duration out. The core never names a provider.

- **First adapter: Whisper, local, in a container** — no key, no cost, nothing leaves the machine.
  Started by an **optional** Compose profile; the quickstart works without it, and an agent whose
  instance has no transcription answers a voice note with the fixed reply, as today.
- **A paid European adapter, later**, behind the same port.

### V4 — Transcription is usage, counted against the spend limit

Each transcription records its usage as a fact — seconds of audio, provider, model — and its price
is a versioned price per minute, by the same rules as the LLM prices (ADR 0010, S1–S2: prices are
deployment data, cost is derived). It **counts towards the client's spend limit** even when it
costs 0, as the local adapter does: the limit then says it, rather than a second path that skips
the guard.

### V5 — Limits: 2 minutes and 20 MB

A voice note over **2 minutes** or **20 MB** (Telegram's limit on what a bot may download) is not
transcribed, and neither is one that transcribes to nothing. It is answered with a fixed text
asking for a written message or a shorter note. Both bounds are platform settings
(`cauce.orchestration.voice-notes.*`).

**Amended in implementation:** the bounds are checked in one place, the worker's transcription
step, not at the webhook: Telegram announces a note's length and size, WhatsApp neither, so only
the worker sees every note the same way. What the channel announces is checked before the download,
the size again on download, and the length the transcriber reports after it (that call is billed).
The invocation fails as `VOICE_NOTE_REJECTED` (public reason `VOICE_NOTE_UNREADABLE`, audited as a
failed invocation), and the fixed text is the agent's reply to a message it cannot read
(`unsupported_message_reply`), else the platform's `cauce.orchestration.fallback.voice-note-reply`:
"try again later", the fallback's sense, would not help.

### V7 — The model reads a voice note as one (decided for 2c)

Before the model, a voice note's transcript is marked `[Transcribed voice note]`, so that the agent
knows it was heard, not written. The sector pack's prompt says what follows: data a mishearing would
change (a phone number, a name, allergens, the number of people) is confirmed before acting on it.
The person and the API read the transcript unmarked.

### V6 — Out of scope

Replies stay text: an agent does not speak. Audio is the only new part type; photos and documents
wait for their own decision.

## Consequences

- One more step on the hot path of a voice message, and its latency (a local Whisper on a CPU is
  seconds per note, more for a long one).
- The usage ledger gains a kind that is not tokens; the usage endpoint and the spend guard read it.
- The eval harness gains voice scenarios: real voice notes as fixtures, with their expected
  transcript, so the agent is tested on what the transcription produced.

## Not decided here

- Which European provider, which Whisper model size, and whether a language hint is passed.
- Whether a stale provider reference is ever refreshed.

## References

- `docs/adr/0004-identity-message-parts-and-channel-capabilities.md` — message parts; the amendment of 2026-10-24 cuts unit 2
- `docs/adr/0005-agentic-loop-hardening.md` — the fallback reply (C3)
- `docs/adr/0006-whatsapp-cloud-api-channel.md` — W5, never silence
- `docs/adr/0010-usage-prices-and-spend-limits.md` — usage as facts, versioned prices, the spend limit
