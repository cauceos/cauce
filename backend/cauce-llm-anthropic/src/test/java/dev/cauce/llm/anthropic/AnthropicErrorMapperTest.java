package dev.cauce.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.llm.exception.LlmParameterRejectedException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmRateLimitException;
import java.util.Set;
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
    void a400NamingASamplingParameterTheRequestSent_isTheAgentsConfigurationRejected() {
        // Regression: claude-sonnet-5-5 answers this when a temperature is sent.
        String body = """
                {"type":"error","error":{"type":"invalid_request_error",
                 "message":"temperature is deprecated for this model"}}
                """;

        LlmProviderException ex = mapper.toException(400, body, "anthropic", "claude-sonnet-5-5", Set.of("temperature"));

        assertThat(ex).isInstanceOfSatisfying(LlmParameterRejectedException.class,
                rejected -> assertThat(rejected.parameter()).isEqualTo("temperature"));
        assertThat(ex.getMessage()).contains("remove it from the agent's configuration")
                .contains("temperature is deprecated for this model");
        // Not sent, not the agent's doing: an ordinary invalid request.
        assertThat(mapper.toException(400, body, "anthropic", "claude-sonnet-5-5", Set.of()))
                .isNotInstanceOf(LlmParameterRejectedException.class)
                .isInstanceOf(LlmInvalidRequestException.class);
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
