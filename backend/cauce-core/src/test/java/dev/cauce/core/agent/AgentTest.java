package dev.cauce.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
    void promptCacheRetention_isShortByDefault_andSurvivesTheOtherEdits() {
        Agent agent = Agent.create(UUID.randomUUID(), "Camarero", "Eres el camarero.", "anthropic", "claude-sonnet-5-5");
        Agent long_ = agent.withPromptCacheRetention(PromptCacheRetention.LONG);

        assertThat(agent.promptCacheRetention()).isEqualTo(PromptCacheRetention.SHORT);
        assertThat(long_.promptCacheRetention()).isEqualTo(PromptCacheRetention.LONG);
        assertThat(long_.edited("Otro", null, null).promptCacheRetention()).isEqualTo(PromptCacheRetention.LONG);
        assertThat(long_.withPastLookupsElided(true).promptCacheRetention()).isEqualTo(PromptCacheRetention.LONG);
        assertThat(long_.retooled(java.util.List.of("search_catalog")).promptCacheRetention())
                .isEqualTo(PromptCacheRetention.LONG);
        assertThatThrownBy(() -> agent.withPromptCacheRetention(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void withPastLookupsElided_isOffByDefault_andSurvivesTheOtherEdits() {
        Agent agent = newAgent();
        Agent eliding = agent.withPastLookupsElided(true);

        assertThat(agent.elidesPastLookups()).isFalse();
        assertThat(eliding.elidesPastLookups()).isTrue();
        assertThat(eliding.updatedAt()).isAfterOrEqualTo(agent.updatedAt());
        assertThat(eliding.edited("Otro", null, null).elidesPastLookups()).isTrue();
        assertThat(eliding.retooled(java.util.List.of("search_catalog")).elidesPastLookups()).isTrue();
        assertThat(eliding.withPastLookupsElided(false).elidesPastLookups()).isFalse();
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
    void create_withoutLlmConfig_leavesTheTemperatureUnset_andBoundsTheReply() {
        Agent agent = Agent.create(TENANT, "Bot", "p", "anthropic", "claude-sonnet-4-7");

        // Regression: a default temperature was sent to every model, and models that no longer
        // accept the parameter rejected every call. Unset means not sent.
        assertThat(agent.temperature()).isEmpty();
        assertThat(agent.maxResponseTokens()).isEqualTo(Agent.DEFAULT_MAX_RESPONSE_TOKENS);
        assertThat(agent.maxResponseTokens()).isEqualTo(4096);
    }

    @Test
    void create_withNullLlmConfig_leavesTheTemperatureUnset() {
        Agent agent = Agent.create(TENANT, "Bot", "p", "anthropic", "claude-sonnet-4-7", null, null);

        assertThat(agent.temperature()).isEmpty();
        assertThat(agent.maxResponseTokens()).isEqualTo(4096);
    }

    @Test
    void create_withExplicitLlmConfig_respectsValues() {
        Agent agent = Agent.create(TENANT, "Bot", "p", "anthropic", "claude-sonnet-4-7", 0.5, 8000);

        assertThat(agent.temperature()).contains(0.5);
        assertThat(agent.maxResponseTokens()).isEqualTo(8000);
    }

    @Test
    void create_acceptsTemperatureAtBounds() {
        assertThat(Agent.create(TENANT, "Bot", "p", "anthropic", "m", 0.0, 1).temperature()).contains(0.0);
        assertThat(Agent.create(TENANT, "Bot", "p", "anthropic", "m", 1.0, 1).temperature()).contains(1.0);
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

    // --- fallback reply (ADR 0005, C3) ---

    @Test
    void create_hasNoFallbackReply_soThePlatformDefaultApplies() {
        Agent agent = Agent.create(UUID.randomUUID(), "Bot", "p", "anthropic", "m");

        assertThat(agent.fallbackReply()).isEmpty();
    }

    @Test
    void withFallbackReply_stripsAndKeepsTheText() {
        Agent agent = Agent.create(UUID.randomUUID(), "Bot", "p", "anthropic", "m")
                .withFallbackReply("  Lo siento, inténtalo más tarde.  ");

        assertThat(agent.fallbackReply()).contains("Lo siento, inténtalo más tarde.");
    }

    @Test
    void withFallbackReply_rejectsBlankAndOverlongText() {
        Agent agent = Agent.create(UUID.randomUUID(), "Bot", "p", "anthropic", "m");

        assertThatThrownBy(() -> agent.withFallbackReply("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> agent.withFallbackReply("x".repeat(Agent.MAX_FALLBACK_REPLY_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(agent.withFallbackReply(null).fallbackReply()).isEmpty();
    }

    @Test
    void edited_replacesThePresentFields_keepsTheRest_andMovesUpdatedAt() throws InterruptedException {
        Agent agent = Agent.create(UUID.randomUUID(), "Bot", "p", "anthropic", "m")
                .withTools(List.of("get_current_time"));
        Thread.sleep(2);

        Agent edited = agent.edited(" Bar ", null, "Sorry.");

        assertThat(edited.id()).isEqualTo(agent.id());
        assertThat(edited.name()).isEqualTo("Bar");
        assertThat(edited.systemPrompt()).isEqualTo("p");
        assertThat(edited.fallbackReply()).contains("Sorry.");
        assertThat(edited.toolNames()).containsExactly("get_current_time");
        assertThat(edited.updatedAt()).isAfter(agent.updatedAt());
        assertThatThrownBy(() -> agent.edited(" ", null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
