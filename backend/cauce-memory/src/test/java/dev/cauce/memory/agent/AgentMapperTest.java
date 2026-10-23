package dev.cauce.memory.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentStatus;
import java.time.Instant;
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

    @Test
    void roundTrip_withExplicitLlmConfig_preservesValues() {
        Agent original = Agent.create(UUID.randomUUID(), "Bot", "prompt", "anthropic",
                "claude-sonnet-4-7", 0.3, 8000);

        Agent roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped.temperature()).contains(0.3);
        assertThat(roundTripped.maxResponseTokens()).isEqualTo(8000);
    }

    @Test
    void roundTrip_preservesWhetherTheAgentElidesPastLookups() {
        Agent plain = Agent.create(UUID.randomUUID(), "Bot", "prompt", "anthropic", "claude-sonnet-5-5");
        Agent eliding = plain.withPastLookupsElided(true);

        assertThat(mapper.toDomain(mapper.toEntity(plain)).elidesPastLookups()).isFalse();
        assertThat(mapper.toDomain(mapper.toEntity(eliding)).elidesPastLookups()).isTrue();
        assertThat(mapper.toDomain(mapper.toEntity(plain)).promptCacheRetention())
                .isEqualTo(dev.cauce.core.agent.PromptCacheRetention.SHORT);
        assertThat(mapper.toDomain(mapper.toEntity(plain.withPromptCacheRetention(
                dev.cauce.core.agent.PromptCacheRetention.LONG))).promptCacheRetention())
                .isEqualTo(dev.cauce.core.agent.PromptCacheRetention.LONG);
        assertThat(mapper.toEntity(eliding).isElidePastLookups()).isTrue();
    }

    @Test
    void toDomain_whenEntityLlmConfigIsNull_leavesTemperatureUnset_andDefaultsTheReplyBound() {
        // A pre-V7 row: temperature and max_response_tokens are NULL in the database.
        Instant now = Instant.now();
        AgentEntity legacy = new AgentEntity(UUID.randomUUID(), UUID.randomUUID(), "Bot", "prompt",
                "anthropic", "claude-sonnet-4-7", null, null, AgentStatus.ACTIVE, now, now);

        Agent domain = mapper.toDomain(legacy);

        assertThat(domain.temperature()).isEmpty();
        assertThat(domain.maxResponseTokens()).isEqualTo(Agent.DEFAULT_MAX_RESPONSE_TOKENS);
    }
}
