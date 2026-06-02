package dev.cauce.llm.openai;

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
 * Manual smoke test against a local Ollama ({@code ollama serve} + {@code ollama pull llama3.2}).
 * Disabled by default: it needs a running Ollama on {@code http://localhost:11434} and no network
 * egress, so it is not viable in CI. Remove {@link Disabled} to run it locally.
 */
@Disabled("Manual: requires a local Ollama running on http://localhost:11434.")
class OpenAiCompatibleLlmProviderManualTest {

    @Test
    void invokesRealOllama() {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleLlmProvider provider = new OpenAiCompatibleLlmProvider(
                "ollama",
                HttpClient.newHttpClient(),
                new OpenAiMessageMapper(objectMapper),
                new OpenAiResponseMapper(objectMapper),
                new OpenAiErrorMapper(objectMapper),
                "http://localhost:11434/v1",
                256,
                Duration.ofSeconds(60));

        LlmResponse response = provider.invoke(LlmInvocation.builder()
                .modelName("llama3.2")
                .messages(List.of(LlmMessage.user("Reply with the single word: pong.")))
                .maxTokens(50)
                .credential(noKey())
                .build());

        System.out.println("Ollama response: " + response.content());
    }

    private static LlmCredential noKey() {
        return new LlmCredential() {
            @Override
            public String getApiKey() {
                return null;
            }

            @Override
            public Optional<String> getOrganizationId() {
                return Optional.empty();
            }
        };
    }
}
