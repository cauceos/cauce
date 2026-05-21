package dev.cauce.core.agent;

/**
 * Lifecycle status of an {@link Agent}. Transitions are introduced in a later
 * commit; for now agents are always created as {@link #DRAFT}.
 */
public enum AgentStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    ARCHIVED
}
