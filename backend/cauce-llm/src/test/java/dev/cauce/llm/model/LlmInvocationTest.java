package dev.cauce.llm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.llm.spi.LlmCredential;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LlmInvocationTest {

    private static final LlmCredential CRED = new LlmCredential() {
        @Override
        public String getApiKey() {
            return "sk-test";
        }

        @Override
        public Optional<String> getOrganizationId() {
            return Optional.empty();
        }
    };

    @Test
    void builder_buildsValidInvocation() {
        LlmInvocation invocation = LlmInvocation.builder()
                .modelName("claude-sonnet-4-7")
                .messages(List.of(LlmMessage.user("Hi")))
                .systemPrompt("You are helpful.")
                .maxTokens(1024)
                .temperature(0.5)
                .credential(CRED)
                .build();

        assertThat(invocation.modelName()).isEqualTo("claude-sonnet-4-7");
        assertThat(invocation.messages()).containsExactly(LlmMessage.user("Hi"));
        assertThat(invocation.systemPrompt()).isEqualTo("You are helpful.");
        assertThat(invocation.maxTokens()).isEqualTo(1024);
        assertThat(invocation.temperature()).isEqualTo(0.5);
        assertThat(invocation.credential()).isSameAs(CRED);
    }

    @Test
    void builder_defaultsToolsToEmpty() {
        LlmInvocation invocation = baseBuilder().build();

        assertThat(invocation.tools()).isEmpty();
    }

    @Test
    void builder_carriesToolDefinitions() {
        ToolDefinition clock = new ToolDefinition("get_current_time", "Returns the time",
                Map.of("type", "object"));

        LlmInvocation invocation = baseBuilder().tools(List.of(clock)).build();

        assertThat(invocation.tools()).containsExactly(clock);
    }

    @Test
    void constructor_rejectsBlankModelName() {
        assertThatThrownBy(() -> baseBuilder().modelName("  ").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullCredential() {
        assertThatThrownBy(() -> baseBuilder().credential(null).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsNullMessages() {
        assertThatThrownBy(() -> baseBuilder().messages(null).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsTemperatureOutOfRange() {
        assertThatThrownBy(() -> baseBuilder().temperature(1.5).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> baseBuilder().temperature(-0.1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNonPositiveMaxTokens() {
        assertThatThrownBy(() -> baseBuilder().maxTokens(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messages_areImmutable() {
        LlmInvocation invocation = baseBuilder().build();

        assertThatThrownBy(() -> invocation.messages().add(LlmMessage.user("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static LlmInvocation.Builder baseBuilder() {
        return LlmInvocation.builder()
                .modelName("claude-sonnet-4-7")
                .messages(List.of(LlmMessage.user("Hi")))
                .credential(CRED);
    }
}
