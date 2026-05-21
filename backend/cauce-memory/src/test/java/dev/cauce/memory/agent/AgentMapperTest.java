package dev.cauce.memory.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentMapperTest {

    private final AgentMapper mapper = new AgentMapper();

    @Test
    void roundTrip_domainToEntityToDomain_preservesAllFields() {
        Agent original = Agent.create(UUID.randomUUID(), "DentalBot",
                "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7");

        Agent roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped.id()).isEqualTo(original.id());
        assertThat(roundTripped.tenantId()).isEqualTo(original.tenantId());
        assertThat(roundTripped.name()).isEqualTo(original.name());
        assertThat(roundTripped.systemPrompt()).isEqualTo(original.systemPrompt());
        assertThat(roundTripped.modelProvider()).isEqualTo(original.modelProvider());
        assertThat(roundTripped.modelName()).isEqualTo(original.modelName());
        assertThat(roundTripped.status()).isEqualTo(AgentStatus.DRAFT);
        assertThat(roundTripped.createdAt()).isEqualTo(original.createdAt());
        assertThat(roundTripped.updatedAt()).isEqualTo(original.updatedAt());
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toEntity_copiesAllFields() {
        Agent agent = Agent.create(UUID.randomUUID(), "Bot", "prompt", "openai", "gpt-5-turbo");

        AgentEntity entity = mapper.toEntity(agent);

        assertThat(entity.getId()).isEqualTo(agent.id());
        assertThat(entity.getTenantId()).isEqualTo(agent.tenantId());
        assertThat(entity.getName()).isEqualTo("Bot");
        assertThat(entity.getModelProvider()).isEqualTo("openai");
        assertThat(entity.getStatus()).isEqualTo(AgentStatus.DRAFT);
    }
}
