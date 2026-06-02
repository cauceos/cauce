package dev.cauce.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenAiCompatibleLlmConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(OpenAiCompatibleLlmConfiguration.class));

    @Test
    void registersOllama_whenEnabled_withoutAnyKey_usingDefaultBaseUrl() {
        runner.withPropertyValues("cauce.llm.openai-compatible.providers.ollama.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("ollamaLlmProvider");
                    OpenAiCompatibleLlmProvider provider =
                            context.getBean("ollamaLlmProvider", OpenAiCompatibleLlmProvider.class);
                    assertThat(provider.id()).isEqualTo("ollama");
                });
    }

    @Test
    void registersNoProvider_whenNothingEnabled() {
        runner.run(context -> assertThat(context).doesNotHaveBean(OpenAiCompatibleLlmProvider.class));
    }

    @Test
    void registersOpenAi_onlyWhenEnabledAndKeyPresent() {
        runner.withPropertyValues(
                        "cauce.llm.openai-compatible.providers.openai.enabled=true",
                        "OPENAI_API_KEY=sk-test")
                .run(context -> {
                    assertThat(context).hasBean("openAiLlmProvider");
                    assertThat(context.getBean("openAiLlmProvider", OpenAiCompatibleLlmProvider.class)
                            .id()).isEqualTo("openai");
                });
    }

    @Test
    void doesNotRegisterOpenAi_whenEnabledButKeyAbsent() {
        runner.withPropertyValues("cauce.llm.openai-compatible.providers.openai.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean("openAiLlmProvider"));
    }

    @Test
    void registersMistral_onlyWhenEnabledAndKeyPresent() {
        runner.withPropertyValues(
                        "cauce.llm.openai-compatible.providers.mistral.enabled=true",
                        "MISTRAL_API_KEY=sk-test")
                .run(context -> assertThat(context).hasBean("mistralLlmProvider"));
    }
}
