package dev.cauce.api.agent;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentStatus;
import dev.cauce.core.agent.PromptCacheRetention;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API representation of an {@link Agent}. Decoupled from the domain type so the wire contract
 * can evolve independently. Serialised in snake_case (global Jackson naming strategy).
 */
public record AgentResponse(
        UUID id,
        UUID tenantId,
        String name,
        String systemPrompt,
        String modelProvider,
        String modelName,
        Double temperature,
        int maxResponseTokens,
        AgentStatus status,
        String fallbackReply,
        String unsupportedMessageReply,
        List<String> tools,
        boolean elidePastLookups,
        PromptCacheRetention promptCacheRetention,
        Instant createdAt,
        Instant updatedAt) {

    public static AgentResponse from(Agent agent) {
        return new AgentResponse(
                agent.id(),
                agent.tenantId(),
                agent.name(),
                agent.systemPrompt(),
                agent.modelProvider(),
                agent.modelName(),
                agent.temperature().orElse(null),
                agent.maxResponseTokens(),
                agent.status(),
                agent.fallbackReply().orElse(null),
                agent.unsupportedMessageReply().orElse(null),
                agent.toolNames(),
                agent.elidesPastLookups(),
                agent.promptCacheRetention(),
                agent.createdAt(),
                agent.updatedAt());
    }
}
