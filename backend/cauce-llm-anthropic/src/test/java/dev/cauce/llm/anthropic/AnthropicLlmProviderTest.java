package dev.cauce.llm.anthropic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.llm.exception.LlmTimeoutException;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.spi.LlmCredential;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@WireMockTest
class AnthropicLlmProviderTest {

    private static final String OK_BODY = """
            {"content":[{"type":"text","text":"Hi there"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":3,"output_tokens":2}}
            """;

    @Test
    void invoke_onSuccess_returnsMappedResponse_andSendsAuthHeaders(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(OK_BODY)));

        LlmResponse response = provider(wm, Duration.ofSeconds(5)).invoke(invocation());

        assertThat(response.content()).isEqualTo("Hi there");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.usage().totalTokens()).isEqualTo(5);
        verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("sk-test"))
                .withHeader("anthropic-version", equalTo("2023-06-01")));
    }

    @Test
    void invoke_on401_throwsAuthentication(WireMockRuntimeInfo wm) {
        stubError(401, "authentication_error", "invalid x-api-key");

        assertThatThrownBy(() -> provider(wm, Duration.ofSeconds(5)).invoke(invocation()))
                .isInstanceOf(LlmAuthenticationException.class);
    }

    @Test
    void invoke_on429_throwsRateLimit(WireMockRuntimeInfo wm) {
        stubError(429, "rate_limit_error", "slow down");

        assertThatThrownBy(() -> provider(wm, Duration.ofSeconds(5)).invoke(invocation()))
                .isInstanceOf(LlmRateLimitException.class);
    }

    @Test
    void invoke_on400_throwsInvalidRequest(WireMockRuntimeInfo wm) {
        stubError(400, "invalid_request_error", "bad model");

        assertThatThrownBy(() -> provider(wm, Duration.ofSeconds(5)).invoke(invocation()))
                .isInstanceOf(LlmInvalidRequestException.class);
    }

    @Test
    void invoke_whenResponseExceedsTimeout_throwsTimeout(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(1000)
                .withBody(OK_BODY)));

        assertThatThrownBy(() -> provider(wm, Duration.ofMillis(300)).invoke(invocation()))
                .isInstanceOf(LlmTimeoutException.class);
    }

    @Test
    void supports_onlyClaudeModels(WireMockRuntimeInfo wm) {
        AnthropicLlmProvider provider = provider(wm, Duration.ofSeconds(5));

        assertThat(provider.supports("claude-sonnet-4-7")).isTrue();
        assertThat(provider.supports("gpt-5-turbo")).isFalse();
        assertThat(provider.supports(null)).isFalse();
        assertThat(provider.id()).isEqualTo("anthropic");
    }

    private static void stubError(int status, String type, String message) {
        stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"" + type
                        + "\",\"message\":\"" + message + "\"}}")));
    }

    private static AnthropicLlmProvider provider(WireMockRuntimeInfo wm, Duration requestTimeout) {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new AnthropicLlmProvider(
                httpClient,
                new AnthropicMessageMapper(objectMapper),
                new AnthropicResponseMapper(objectMapper),
                new AnthropicErrorMapper(objectMapper),
                wm.getHttpBaseUrl(),
                "2023-06-01",
                4096,
                requestTimeout);
    }

    private static LlmInvocation invocation() {
        return LlmInvocation.builder()
                .modelName("claude-sonnet-4-7")
                .messages(List.of(LlmMessage.user("Hi")))
                .credential(cred())
                .build();
    }

    private static LlmCredential cred() {
        return new LlmCredential() {
            @Override
            public String getApiKey() {
                return "sk-test";
            }

            @Override
            public Optional<String> getOrganizationId() {
                return Optional.empty();
            }
        };
    }
}
