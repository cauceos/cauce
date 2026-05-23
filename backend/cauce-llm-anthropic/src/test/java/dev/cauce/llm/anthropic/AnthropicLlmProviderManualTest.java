package dev.cauce.llm.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.spi.LlmCredential;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Manual smoke test against the real Anthropic API. Disabled by default: it needs a valid
 * {@code ANTHROPIC_API_KEY} in the environment and outbound network access, so it is not
 * viable in CI. Remove {@link Disabled} to run it locally.
 */
@Disabled("Manual: requires a real ANTHROPIC_API_KEY and network access.")
class AnthropicLlmProviderManualTest {

    @Test
    void invokesRealAnthropic() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        ObjectMapper objectMapper = new ObjectMapper();
        AnthropicLlmProvider provider = new AnthropicLlmProvider(
                HttpClient.newHttpClient(),
                new AnthropicMessageMapper(objectMapper),
                new AnthropicResponseMapper(objectMapper),
                new AnthropicErrorMapper(objectMapper),
                "https://api.anthropic.com",
                "2023-06-01",
                1024,
                Duration.ofSeconds(60));

        LlmResponse response = provider.invoke(LlmInvocation.builder()
                .modelName("claude-sonnet-4-7")
                .messages(List.of(LlmMessage.user("Reply with the single word: pong.")))
                .maxTokens(50)
                .credential(new LlmCredential() {
                    @Override
                    public String getApiKey() {
                        return apiKey;
                    }

                    @Override
                    public Optional<String> getOrganizationId() {
                        return Optional.empty();
                    }
                })
                .build());

        System.out.println("Anthropic response: " + response.content());
    }
}
