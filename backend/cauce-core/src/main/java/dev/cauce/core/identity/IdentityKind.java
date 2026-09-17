package dev.cauce.core.identity;

/**
 * The closed vocabulary of what an {@link Identity}'s {@code value} is (ADR 0004, decision 1).
 * A domain enum with a CHECK constraint in the database, deliberately not free text: if it
 * were, one adapter would write {@code phone} and another {@code phone_number}, and the
 * mismatch would surface only when the two had to be matched. Adding a kind is a migration
 * of the CHECK, because it is a domain change and should look like one.
 *
 * <p>The core knows the <em>names</em> of the kinds, never their formats. Whether a value is
 * a well-formed telephone number, whether two spellings are the same number, or whether a
 * provider id designates a person, belongs to the adapter that produced the value.
 */
public enum IdentityKind {

    /** A telephone number (WhatsApp, voice). */
    PHONE_NUMBER,

    /** An e-mail address (email). */
    EMAIL_ADDRESS,

    /**
     * An identifier minted by the provider, meaningful only within the identity's
     * {@code channelType}. Telegram stamps {@code chat.id} here: for a private chat it is the
     * user's id, for a group it is the group's, so the thread is the chat, not a person.
     */
    PROVIDER_USER_ID,

    /** Whatever the REST caller supplies on the reserved {@code api} channel. */
    CLIENT_REFERENCE,

    /** A web-chat session. */
    SESSION_ID
}
