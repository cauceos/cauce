package dev.cauce.llm.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmTimeoutException;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.spi.LlmProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link LlmProvider} for Anthropic Claude, calling the Messages API over the JDK
 * {@link HttpClient} (no vendor SDK, no third-party HTTP client). Stateless and
 * thread-safe: a single instance serves concurrent invocations. The API key travels with
 * the invocation's credential, not with this provider, so the same instance works for
 * system-wide and (later) per-tenant credentials. No automatic retries in v1.0; failures
 * propagate as SPI exceptions for an upper layer to handle.
 */
public final class AnthropicLlmProvider implements LlmProvider {

    static final String PROVIDER_ID = "anthropic";
    private static final String MODELS_PATH = "/v1/messages";

    private final HttpClient httpClient;
    private final AnthropicMessageMapper messageMapper;
    private final AnthropicResponseMapper responseMapper;
    private final AnthropicErrorMapper errorMapper;
    private final String baseUrl;
    private final String anthropicVersion;
    private final int defaultMaxTokens;
    private final Duration requestTimeout;

    public AnthropicLlmProvider(HttpClient httpClient,
                                AnthropicMessageMapper messageMapper,
                                AnthropicResponseMapper responseMapper,
                                AnthropicErrorMapper errorMapper,
                                String baseUrl,
                                String anthropicVersion,
                                int defaultMaxTokens,
                                Duration requestTimeout) {
        this.httpClient = httpClient;
        this.messageMapper = messageMapper;
        this.responseMapper = responseMapper;
        this.errorMapper = errorMapper;
        this.baseUrl = baseUrl;
        this.anthropicVersion = anthropicVersion;
        this.defaultMaxTokens = defaultMaxTokens;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supports(String modelName) {
        return modelName != null && modelName.startsWith("claude-");
    }

    @Override
    public LlmResponse invoke(LlmInvocation invocation) {
        String model = invocation.modelName();

        String apiKey = invocation.credential() == null ? null : invocation.credential().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmAuthenticationException(PROVIDER_ID, model, "Missing Anthropic API key");
        }

        String requestBody;
        try {
            requestBody = messageMapper.toRequestJson(invocation, defaultMaxTokens);
        } catch (JsonProcessingException e) {
            throw new LlmProviderException(PROVIDER_ID, model, "Failed to serialize request", e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + MODELS_PATH))
                .timeout(requestTimeout)
                .header("x-api-key", apiKey)
                .header("anthropic-version", anthropicVersion)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LlmTimeoutException(PROVIDER_ID, model, "Request timed out after " + requestTimeout, e);
        } catch (IOException e) {
            throw new LlmProviderException(PROVIDER_ID, model, "HTTP call to Anthropic failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException(PROVIDER_ID, model, "Interrupted during Anthropic call", e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            try {
                return responseMapper.toDomain(response.body());
            } catch (JsonProcessingException e) {
                throw new LlmProviderException(PROVIDER_ID, model, "Failed to parse Anthropic response", e);
            }
        }
        throw errorMapper.toException(status, response.body(), PROVIDER_ID, model);
    }
}
