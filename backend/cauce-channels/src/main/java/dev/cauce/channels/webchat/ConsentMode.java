package dev.cauce.channels.webchat;

/**
 * How a web chat asks for the person's consent before their first message (ADR 0014, WC5). Which one
 * applies is the data controller's choice, the shop's, not Cauce's.
 */
public enum ConsentMode {

    /** An informational notice with a link to the shop's privacy policy (the default). */
    NOTICE,

    /** Explicit acceptance before the first message, recorded on the session. */
    ACCEPTANCE
}
