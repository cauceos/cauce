package dev.cauce.business.gap;

import dev.cauce.core.business.gap.KnowledgeGap;
import org.springframework.stereotype.Component;

/** Hand-written mapper between {@link KnowledgeGap} and {@link KnowledgeGapEntity}. */
@Component
public class KnowledgeGapMapper {

    public KnowledgeGapEntity toEntity(KnowledgeGap gap) {
        return new KnowledgeGapEntity(gap.id(), gap.tenantId(), gap.agentId(), gap.conversationId(), gap.question(),
                gap.createdAt());
    }

    public KnowledgeGap toDomain(KnowledgeGapEntity entity) {
        return new KnowledgeGap(entity.getId(), entity.getTenantId(), entity.getAgentId(), entity.getConversationId(),
                entity.getQuestion(), entity.getCreatedAt());
    }
}
