package dev.cauce.business.handoff;

import dev.cauce.core.business.handoff.Handoff;
import org.springframework.stereotype.Component;

/** Hand-written mapper between {@link Handoff} and {@link HandoffEntity}. */
@Component
public class HandoffMapper {

    public HandoffEntity toEntity(Handoff handoff) {
        return new HandoffEntity(handoff.id(), handoff.tenantId(), handoff.code(), handoff.agentId(),
                handoff.conversationId(), handoff.identityId(), handoff.category(), handoff.summary(),
                handoff.contactName(), handoff.contactPhone().orElse(null), handoff.status(), handoff.createdAt(),
                handoff.resolvedAt().orElse(null));
    }

    public Handoff toDomain(HandoffEntity entity) {
        return Handoff.rehydrate(entity.getId(), entity.getTenantId(), entity.getCode(), entity.getAgentId(),
                entity.getConversationId(), entity.getIdentityId(), entity.getCategory(), entity.getSummary(),
                entity.getContactName(), entity.getContactPhone(), entity.getStatus(), entity.getCreatedAt(),
                entity.getResolvedAt());
    }
}
