package dev.cauce.llm.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Anthropic provider bean when the adapter is enabled
 * ({@code cauce.llm.anthropic.enabled}, default true) and an {@code ANTHROPIC_API_KEY} is
 * present. When the key is absent the bean is not created, so
 * {@code LlmProviderRegistry.getProvider("anthropic")} is empty and the application still
 * starts normally. Discovered by the application's {@code dev.cauce} component scan.
 */
@Configuration
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicLlmConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "cauce.llm.anthropic", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    @Conditional(AnthropicApiKeyCondition.class)
    public AnthropicLlmProvider anthropicLlmProvider(AnthropicProperties properties) {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build();
        return new AnthropicLlmProvider(
                httpClient,
                new AnthropicMessageMapper(objectMapper),
                new AnthropicResponseMapper(objectMapper),
                new AnthropicErrorMapper(objectMapper),
                properties.getBaseUrl(),
                properties.getVersion(),
                properties.getMaxTokens(),
                properties.getTimeout());
    }
}
