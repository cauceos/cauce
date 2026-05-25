package dev.cauce.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void create_generatesUuidV7() {
        assertThat(newAgent().id().version()).isEqualTo(7);
    }

    @Test
    void create_assignsDraftStatus() {
        assertThat(newAgent().status()).isEqualTo(AgentStatus.DRAFT);
    }

    @Test
    void create_assignsFieldsAndTimestamps() {
        Agent agent = Agent.create(TENANT, "DentalBot", "You are a dentist receptionist.",
                "anthropic", "claude-sonnet-4-7");

        assertThat(agent.tenantId()).isEqualTo(TENANT);
        assertThat(agent.name()).isEqualTo("DentalBot");
        assertThat(agent.systemPrompt()).isEqualTo("You are a dentist receptionist.");
        assertThat(agent.modelProvider()).isEqualTo("anthropic");
        assertThat(agent.modelName()).isEqualTo("claude-sonnet-4-7");
        assertThat(agent.createdAt()).isNotNull();
        assertThat(agent.updatedAt()).isNotNull();
    }

    @Test
    void create_allowsEmptySystemPrompt() {
        assertThat(Agent.create(TENANT, "Bot", "", "anthropic", "claude-sonnet-4-7").systemPrompt())
                .isEmpty();
    }

    @Test
    void create_rejectsNullTenantId() {
        assertThatThrownBy(() -> Agent.create(null, "Bot", "p", "anthropic", "m"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_rejectsBlankName() {
        assertThatThrownBy(() -> Agent.create(TENANT, "  ", "p", "anthropic", "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsBlankModelProvider() {
        assertThatThrownBy(() -> Agent.create(TENANT, "Bot", "p", " ", "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsBlankModelName() {
        assertThatThrownBy(() -> Agent.create(TENANT, "Bot", "p", "anthropic", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_withoutLlmConfig_appliesDefaults() {
        Agent agent = Agent.create(TENANT, "Bot", "p", "anthropic", "claude-sonnet-4-7");

        assertThat(agent.temperature()).isEqualTo(Agent.DEFAULT_TEMPERATURE);
        assertThat(agent.maxResponseTokens()).isEqualTo(Agent.DEFAULT_MAX_RESPONSE_TOKENS);
        assertThat(agent.temperature()).isEqualTo(0.7);
        assertThat(agent.maxResponseTokens()).isEqualTo(4096);
    }

    @Test
    void create_withNullLlmConfig_appliesDefaults() {
        Agent agent = Agent.create(TENANT, "Bot", "p", "anthropic", "claude-sonnet-4-7", null, null);

        assertThat(agent.temperature()).isEqualTo(0.7);
        assertThat(agent.maxResponseTokens()).isEqualTo(4096);
    }

    @Test
    void create_withExplicitLlmConfig_respectsValues() {
        Agent agent = Agent.create(TENANT, "Bot", "p", "anthropic", "claude-sonnet-4-7", 0.5, 8000);

        assertThat(agent.temperature()).isEqualTo(0.5);
        assertThat(agent.maxResponseTokens()).isEqualTo(8000);
    }

    @Test
    void create_acceptsTemperatureAtBounds() {
        assertThat(Agent.create(TENANT, "Bot", "p", "anthropic", "m", 0.0, 1).temperature()).isZero();
        assertThat(Agent.create(TENANT, "Bot", "p", "anthropic", "m", 1.0, 1).temperature())
                .isEqualTo(1.0);
    }

    @Test
    void create_rejectsTemperatureAboveOne() {
        assertThatThrownBy(() -> Agent.create(TENANT, "Bot", "p", "anthropic", "m", 1.5, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsNegativeTemperature() {
        assertThatThrownBy(() -> Agent.create(TENANT, "Bot", "p", "anthropic", "m", -0.1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsZeroMaxResponseTokens() {
        assertThatThrownBy(() -> Agent.create(TENANT, "Bot", "p", "anthropic", "m", null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsNegativeMaxResponseTokens() {
        assertThatThrownBy(() -> Agent.create(TENANT, "Bot", "p", "anthropic", "m", null, -100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalsAndHashCode_areBasedOnId() {
        Agent a = newAgent();
        Agent sameId = Agent.rehydrate(a.id(), TENANT, "other", "other", "openai", "gpt-5-turbo",
                0.5, 8000, AgentStatus.ACTIVE, a.createdAt(), a.updatedAt());
        Agent different = newAgent();

        assertThat(a).isEqualTo(sameId);
        assertThat(a).hasSameHashCodeAs(sameId);
        assertThat(a).isNotEqualTo(different);
    }

    private static Agent newAgent() {
        return Agent.create(TENANT, "DentalBot", "prompt", "anthropic", "claude-sonnet-4-7");
    }
}
