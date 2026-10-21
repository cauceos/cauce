package dev.cauce.api.business;

import dev.cauce.core.business.handoff.Handoff;
import java.time.Instant;
import java.util.UUID;

/** A case an agent handed to the owner. */
public record HandoffResponse(
        UUID id,
        UUID tenantId,
        String code,
        UUID agentId,
        UUID conversationId,
        UUID identityId,
        String category,
        String summary,
        String contactName,
        String contactPhone,
        String status,
        Instant createdAt,
        Instant resolvedAt) {

    public static HandoffResponse from(Handoff handoff) {
        return new HandoffResponse(handoff.id(), handoff.tenantId(), handoff.code(), handoff.agentId(),
                handoff.conversationId(), handoff.identityId(), handoff.category(), handoff.summary(),
                handoff.contactName(), handoff.contactPhone().orElse(null), handoff.status().name(),
                handoff.createdAt(), handoff.resolvedAt().orElse(null));
    }
}
