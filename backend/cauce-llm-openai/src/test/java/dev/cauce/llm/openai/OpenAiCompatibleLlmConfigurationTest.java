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
    void ollama_withoutAContextWindowSetting_servesOllamasDefaultContextLength() {
        // What Ollama serves when nothing raises it (0.40.8: 4096). Assuming more than is served
        // lets the orchestrator send history the server silently cuts.
        runner.withPropertyValues("cauce.llm.openai-compatible.providers.ollama.enabled=true")
                .run(context -> assertThat(context.getBean("ollamaLlmProvider", OpenAiCompatibleLlmProvider.class)
                        .servedContextWindow("qwen2.5:3b")).hasValue(4096));
    }

    @Test
    void ollama_withAContextWindowSetting_servesThatWindow() {
        runner.withPropertyValues("cauce.llm.openai-compatible.providers.ollama.enabled=true",
                        "cauce.llm.openai-compatible.providers.ollama.context-window=16384")
                .run(context -> assertThat(context.getBean("ollamaLlmProvider", OpenAiCompatibleLlmProvider.class)
                        .servedContextWindow("qwen2.5:3b")).hasValue(16384));
    }

    @Test
    void ollama_withANonPositiveContextWindow_failsAtStartup() {
        runner.withPropertyValues("cauce.llm.openai-compatible.providers.ollama.enabled=true",
                        "cauce.llm.openai-compatible.providers.ollama.context-window=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void hostedProvider_leavesTheWindowToTheModel() {
        runner.withPropertyValues(
                        "cauce.llm.openai-compatible.providers.openai.enabled=true",
                        "OPENAI_API_KEY=sk-test")
                .run(context -> assertThat(context.getBean("openAiLlmProvider", OpenAiCompatibleLlmProvider.class)
                        .servedContextWindow("gpt-4o")).isEmpty());
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
        // Blank, not merely unset: a key exported in the shell running the build would otherwise
        // reach the context through the system environment.
        runner.withPropertyValues("cauce.llm.openai-compatible.providers.openai.enabled=true", "OPENAI_API_KEY=")
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
