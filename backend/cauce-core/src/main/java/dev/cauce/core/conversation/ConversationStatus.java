package dev.cauce.core.conversation;

/**
 * Lifecycle status of a {@link Conversation}. State transitions (close, escalate,
 * archive) are introduced in a later commit; for now conversations are always
 * created as {@link #OPEN}.
 */
public enum ConversationStatus {
    OPEN,
    CLOSED,
    ESCALATED,
    ARCHIVED
}
