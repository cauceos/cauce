package dev.cauce.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.openai.OpenAiCompatibleProperties.ProviderSettings;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Registers an {@link OpenAiCompatibleLlmProvider} per enabled OpenAI-compatible endpoint. One
 * conditional {@code @Bean} per known provider id ({@code ollama}, {@code openai}, {@code mistral}):
 * each is created only when {@code cauce.llm.openai-compatible.providers.<id>.enabled=true}; the
 * keyed providers (OpenAI, Mistral) additionally require their API key in the environment, mirroring
 * the Anthropic adapter. Ollama needs no key (local, no egress) — it is the dev-friendly default
 * (enabled in {@code application-dev.properties}). Discovered by the application's {@code dev.cauce}
 * component scan; adding another OpenAI-compatible provider is a new {@code @Bean} method here.
 */
@Configuration
@EnableConfigurationProperties(OpenAiCompatibleProperties.class)
public class OpenAiCompatibleLlmConfiguration {

    static final String OLLAMA_ID = "ollama";
    static final String OPENAI_ID = "openai";
    static final String MISTRAL_ID = "mistral";

    static final String OLLAMA_DEFAULT_BASE_URL = "http://localhost:11434/v1";
    static final String OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1";
    static final String MISTRAL_DEFAULT_BASE_URL = "https://api.mistral.ai/v1";

    @Bean
    @ConditionalOnProperty(prefix = "cauce.llm.openai-compatible.providers.ollama",
            name = "enabled", havingValue = "true")
    public OpenAiCompatibleLlmProvider ollamaLlmProvider(OpenAiCompatibleProperties properties) {
        return buildProvider(OLLAMA_ID, properties, OLLAMA_DEFAULT_BASE_URL);
    }

    @Bean
    @ConditionalOnProperty(prefix = "cauce.llm.openai-compatible.providers.openai",
            name = "enabled", havingValue = "true")
    @Conditional(OpenAiApiKeyCondition.class)
    public OpenAiCompatibleLlmProvider openAiLlmProvider(OpenAiCompatibleProperties properties) {
        return buildProvider(OPENAI_ID, properties, OPENAI_DEFAULT_BASE_URL);
    }

    @Bean
    @ConditionalOnProperty(prefix = "cauce.llm.openai-compatible.providers.mistral",
            name = "enabled", havingValue = "true")
    @Conditional(MistralApiKeyCondition.class)
    public OpenAiCompatibleLlmProvider mistralLlmProvider(OpenAiCompatibleProperties properties) {
        return buildProvider(MISTRAL_ID, properties, MISTRAL_DEFAULT_BASE_URL);
    }

    private static OpenAiCompatibleLlmProvider buildProvider(String id,
                                                             OpenAiCompatibleProperties properties,
                                                             String defaultBaseUrl) {
        ProviderSettings settings = properties.getProviders().getOrDefault(id, new ProviderSettings());
        String baseUrl = (settings.getBaseUrl() != null && !settings.getBaseUrl().isBlank())
                ? settings.getBaseUrl()
                : defaultBaseUrl;

        ObjectMapper objectMapper = new ObjectMapper();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(settings.getTimeout())
                .build();
        return new OpenAiCompatibleLlmProvider(
                id,
                httpClient,
                new OpenAiMessageMapper(objectMapper),
                new OpenAiResponseMapper(objectMapper),
                new OpenAiErrorMapper(objectMapper),
                baseUrl,
                settings.getMaxTokens(),
                settings.getTimeout());
    }
}
