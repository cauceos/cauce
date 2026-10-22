package dev.cauce.api.business;

import dev.cauce.core.business.handoff.Handoff;
import java.time.Instant;
import java.util.UUID;

/**
 * A case an agent handed to the owner. {@code summary} is the agent's account; {@code customerMessage}
 * the person's own words, when the agent quoted them.
 */
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
        String customerMessage,
        String status,
        Instant createdAt,
        Instant resolvedAt) {

    public static HandoffResponse from(Handoff handoff) {
        return new HandoffResponse(handoff.id(), handoff.tenantId(), handoff.code(), handoff.agentId(),
                handoff.conversationId(), handoff.identityId(), handoff.category(), handoff.summary(),
                handoff.contactName(), handoff.contactPhone().orElse(null), handoff.customerMessage().orElse(null),
                handoff.status().name(),
                handoff.createdAt(), handoff.resolvedAt().orElse(null));
    }
}
