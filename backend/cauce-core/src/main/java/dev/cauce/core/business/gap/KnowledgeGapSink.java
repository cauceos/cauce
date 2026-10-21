package dev.cauce.core.business.gap;

import java.util.UUID;

/**
 * Port: where an agent records a question it could not answer from what the business loaded
 * (ADR 0007, B4). The owner reads them in the daily digest and fills the gap in the profile or
 * the catalog.
 */
public interface KnowledgeGapSink {

    /** Records {@code question}, asked in {@code conversationId} of {@code agentId}. */
    KnowledgeGap recordUnansweredQuestion(UUID tenantId, UUID agentId, UUID conversationId, String question);
}
