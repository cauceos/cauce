package dev.cauce.api.business;

import dev.cauce.core.business.gap.KnowledgeGap;
import java.time.Instant;
import java.util.UUID;

/** A question an agent could not answer from the business's profile or catalog. */
public record UnansweredQuestionResponse(
        UUID id,
        UUID tenantId,
        UUID agentId,
        UUID conversationId,
        String question,
        Instant createdAt) {

    public static UnansweredQuestionResponse from(KnowledgeGap gap) {
        return new UnansweredQuestionResponse(gap.id(), gap.tenantId(), gap.agentId(), gap.conversationId(),
                gap.question(), gap.createdAt());
    }
}
