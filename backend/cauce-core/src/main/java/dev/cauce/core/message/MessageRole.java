package dev.cauce.core.message;

/**
 * The author role of a {@link Message} within a conversation.
 *
 * <p>Deliberately minimal: only the roles needed today. Richer roles (e.g. human-operator
 * handover) are introduced when the corresponding features land, not speculatively.
 */
public enum MessageRole {
    /** Sent by the external user (patient, customer, citizen). */
    USER,
    /** Produced by the agent. */
    AGENT,
    /** System-authored message (e.g. priming or control instructions). */
    SYSTEM,
    /** A tool invocation the agent requested; the structured call is the message's tool content. */
    TOOL_CALL,
    /** The result of executing a tool call; the structured result is the message's tool content. */
    TOOL_RESULT
}
