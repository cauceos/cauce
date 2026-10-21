package dev.cauce.core.identity;

/**
 * Events about interlocutor identities (ADR 0004, implementation decision PC-3). Observational
 * only: published on the application event bus, never written to the audit hash chain, and
 * carrying opaque ids and non-sensitive metadata only — never the identity's value, so that
 * erasing an identity row leaves nothing personal behind in any event.
 *
 * <p>Published synchronously inside the transaction that created the fact. There are no
 * consumers; the first one that persists must listen {@code AFTER_COMMIT} (or read from an
 * outbox), exactly as for the orchestration event stream.
 */
public sealed interface IdentityEvent permits IdentityCreated {

    /** The stable event type name, in the {@code conduct.*} vocabulary. */
    String type();
}
