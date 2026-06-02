package dev.cauce.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmRateLimitException;
import org.junit.jupiter.api.Test;

class OpenAiErrorMapperTest {

    private final OpenAiErrorMapper mapper = new OpenAiErrorMapper(new ObjectMapper());

    @Test
    void maps401ToAuthentication_andExtractsOpenAiErrorObject() {
        String body = "{\"error\":{\"message\":\"Invalid API key\",\"type\":\"invalid_request_error\"}}";

        LlmProviderException e = mapper.toException(401, body, "openai", "gpt-4o");

        assertThat(e).isInstanceOf(LlmAuthenticationException.class);
        assertThat(e.getMessage()).contains("Invalid API key");
    }

    @Test
    void maps429ToRateLimit() {
        LlmProviderException e =
                mapper.toException(429, "{\"error\":{\"message\":\"slow down\"}}", "openai", "gpt-4o");

        assertThat(e).isInstanceOf(LlmRateLimitException.class);
    }

    @Test
    void maps404ToInvalidRequest_andExtractsOllamaStringError() {
        // Ollama returns the error as a plain string, not an object.
        LlmProviderException e =
                mapper.toException(404, "{\"error\":\"model 'x' not found\"}", "ollama", "x");

        assertThat(e).isInstanceOf(LlmInvalidRequestException.class);
        assertThat(e.getMessage()).contains("model 'x' not found");
    }

    @Test
    void mapsUnknownStatusToGenericException_andToleratesMalformedBody() {
        LlmProviderException e = mapper.toException(500, "not json at all", "ollama", "llama3.2");

        assertThat(e).isExactlyInstanceOf(LlmProviderException.class);
        assertThat(e.getMessage()).contains("HTTP 500");
    }
}
