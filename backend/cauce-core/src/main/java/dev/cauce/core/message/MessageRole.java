package dev.cauce.core.message;

/**
 * The author role of a {@link Message} within a conversation.
 *
 * <p>Deliberately minimal: only the roles needed today. Richer roles (e.g. tool
 * results or human-operator handover) are introduced when the corresponding features
 * land, not speculatively.
 */
public enum MessageRole {
    /** Sent by the external user (patient, customer, citizen). */
    USER,
    /** Produced by the agent. */
    AGENT,
    /** System-authored message (e.g. priming or control instructions). */
    SYSTEM
}
