package dev.cauce.api.agent;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentStatus;
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
        double temperature,
        int maxResponseTokens,
        AgentStatus status,
        String fallbackReply,
        List<String> tools,
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
                agent.temperature(),
                agent.maxResponseTokens(),
                agent.status(),
                agent.fallbackReply().orElse(null),
                agent.toolNames(),
                agent.createdAt(),
                agent.updatedAt());
    }
}
