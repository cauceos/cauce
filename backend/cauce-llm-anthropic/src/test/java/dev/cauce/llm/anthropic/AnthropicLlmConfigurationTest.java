package dev.cauce.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AnthropicLlmConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(AnthropicLlmConfiguration.class));

    @Test
    void registersProvider_whenApiKeyPresent() {
        runner.withPropertyValues("ANTHROPIC_API_KEY=sk-test")
                .run(context -> assertThat(context).hasSingleBean(AnthropicLlmProvider.class));
    }

    @Test
    void doesNotRegisterProvider_whenApiKeyAbsent() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AnthropicLlmProvider.class));
    }

    @Test
    void doesNotRegisterProvider_whenDisabled() {
        runner.withPropertyValues("ANTHROPIC_API_KEY=sk-test", "cauce.llm.anthropic.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AnthropicLlmProvider.class));
    }
}
