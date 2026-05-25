package dev.cauce.memory.agent;

import dev.cauce.core.agent.Agent;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link Agent} and its JPA
 * {@link AgentEntity}. No external mapping library is used.
 */
@Component
public final class AgentMapper {

    public AgentEntity toEntity(Agent agent) {
        return new AgentEntity(
                agent.id(),
                agent.tenantId(),
                agent.name(),
                agent.systemPrompt(),
                agent.modelProvider(),
                agent.modelName(),
                agent.temperature(),
                agent.maxResponseTokens(),
                agent.status(),
                agent.createdAt(),
                agent.updatedAt());
    }

    public Agent toDomain(AgentEntity entity) {
        return Agent.rehydrate(
                entity.getId(),
                entity.getTenantId(),
                entity.getName(),
                entity.getSystemPrompt(),
                entity.getModelProvider(),
                entity.getModelName(),
                entity.getTemperature(),
                entity.getMaxResponseTokens(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
