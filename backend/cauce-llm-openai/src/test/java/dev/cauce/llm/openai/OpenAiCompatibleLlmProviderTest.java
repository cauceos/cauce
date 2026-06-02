package dev.cauce.llm.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
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
class OpenAiCompatibleLlmProviderTest {

    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant","content":"Hi there"},
             "finish_reason":"stop"}],
             "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
            """;

    @Test
    void invoke_onSuccess_returnsMappedResponse_andSendsBearerAuth(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(OK_BODY)));

        LlmResponse response = provider(wm, Duration.ofSeconds(5)).invoke(invocation("sk-test"));

        assertThat(response.content()).isEqualTo("Hi there");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.usage().totalTokens()).isEqualTo(10);
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test")));
    }

    @Test
    void invoke_withoutApiKey_sendsNoAuthHeader(WireMockRuntimeInfo wm) {
        // Local Ollama: no key configured -> no Authorization header is sent.
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(OK_BODY)));

        provider(wm, Duration.ofSeconds(5)).invoke(invocation(null));

        verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void invoke_on401_throwsAuthentication(WireMockRuntimeInfo wm) {
        stubError(401, "{\"error\":{\"message\":\"invalid key\"}}");

        assertThatThrownBy(() -> provider(wm, Duration.ofSeconds(5)).invoke(invocation("bad")))
                .isInstanceOf(LlmAuthenticationException.class);
    }

    @Test
    void invoke_on429_throwsRateLimit(WireMockRuntimeInfo wm) {
        stubError(429, "{\"error\":{\"message\":\"slow down\"}}");

        assertThatThrownBy(() -> provider(wm, Duration.ofSeconds(5)).invoke(invocation("sk-test")))
                .isInstanceOf(LlmRateLimitException.class);
    }

    @Test
    void invoke_on400_throwsInvalidRequest(WireMockRuntimeInfo wm) {
        stubError(400, "{\"error\":{\"message\":\"bad model\"}}");

        assertThatThrownBy(() -> provider(wm, Duration.ofSeconds(5)).invoke(invocation("sk-test")))
                .isInstanceOf(LlmInvalidRequestException.class);
    }

    @Test
    void invoke_whenResponseExceedsTimeout_throwsTimeout(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(1000)
                .withBody(OK_BODY)));

        assertThatThrownBy(() -> provider(wm, Duration.ofMillis(300)).invoke(invocation("sk-test")))
                .isInstanceOf(LlmTimeoutException.class);
    }

    @Test
    void supports_anyNonBlankModel_andExposesConfiguredId(WireMockRuntimeInfo wm) {
        OpenAiCompatibleLlmProvider provider = provider(wm, Duration.ofSeconds(5));

        assertThat(provider.supports("gpt-4o")).isTrue();
        assertThat(provider.supports("llama3.2")).isTrue();
        assertThat(provider.supports(" ")).isFalse();
        assertThat(provider.supports(null)).isFalse();
        assertThat(provider.id()).isEqualTo("openai");
    }

    private static void stubError(int status, String body) {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private static OpenAiCompatibleLlmProvider provider(WireMockRuntimeInfo wm, Duration requestTimeout) {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new OpenAiCompatibleLlmProvider(
                "openai",
                httpClient,
                new OpenAiMessageMapper(objectMapper),
                new OpenAiResponseMapper(objectMapper),
                new OpenAiErrorMapper(objectMapper),
                wm.getHttpBaseUrl(),
                4096,
                requestTimeout);
    }

    private static LlmInvocation invocation(String apiKey) {
        return LlmInvocation.builder()
                .modelName("gpt-4o")
                .messages(List.of(LlmMessage.user("Hi")))
                .credential(cred(apiKey))
                .build();
    }

    private static LlmCredential cred(String apiKey) {
        return new LlmCredential() {
            @Override
            public String getApiKey() {
                return apiKey;
            }

            @Override
            public Optional<String> getOrganizationId() {
                return Optional.empty();
            }
        };
    }
}
