package dev.cauce.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmRateLimitException;
import org.junit.jupiter.api.Test;

class AnthropicErrorMapperTest {

    private final AnthropicErrorMapper mapper = new AnthropicErrorMapper(new ObjectMapper());

    @Test
    void maps401ToAuthentication_andExtractsMessage() {
        String body = """
                {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}
                """;

        LlmProviderException ex = mapper.toException(401, body, "anthropic", "claude-sonnet-4-7");

        assertThat(ex).isInstanceOf(LlmAuthenticationException.class);
        assertThat(ex.getMessage()).contains("invalid x-api-key");
        assertThat(ex.providerId()).isEqualTo("anthropic");
        assertThat(ex.modelName()).isEqualTo("claude-sonnet-4-7");
    }

    @Test
    void maps429ToRateLimit() {
        String body = """
                {"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}
                """;

        assertThat(mapper.toException(429, body, "anthropic", "m"))
                .isInstanceOf(LlmRateLimitException.class);
    }

    @Test
    void maps400ToInvalidRequest() {
        String body = """
                {"type":"error","error":{"type":"invalid_request_error","message":"bad model"}}
                """;

        assertThat(mapper.toException(400, body, "anthropic", "m"))
                .isInstanceOf(LlmInvalidRequestException.class);
    }

    @Test
    void mapsOtherStatusesToBaseProviderException() {
        String body = """
                {"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}
                """;

        LlmProviderException ex = mapper.toException(529, body, "anthropic", "m");

        assertThat(ex).isExactlyInstanceOf(LlmProviderException.class);
    }

    @Test
    void malformedBody_stillProducesExceptionWithStatus() {
        LlmProviderException ex = mapper.toException(503, "not json at all", "anthropic", "m");

        assertThat(ex).isExactlyInstanceOf(LlmProviderException.class);
        assertThat(ex.getMessage()).contains("503");
    }
}
