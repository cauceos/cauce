package dev.cauce.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmTimeoutException;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.spi.LlmCredential;
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
 * {@link LlmProvider} for any OpenAI-compatible chat-completions endpoint (OpenAI, Mistral, Ollama),
 * calling {@code POST {baseUrl}/chat/completions} over the JDK {@link HttpClient} (no vendor SDK, no
 * third-party HTTP client). The same class serves several providers: each registered instance has
 * its own {@code id} and {@code baseUrl}, and the registry routes by the agent's {@code modelProvider}.
 *
 * <p>Stateless and thread-safe — a single instance serves concurrent invocations. The API key
 * travels with the invocation's credential, not with this provider, so the same instance works for
 * system-wide and (later) per-tenant credentials. When no key is present the {@code Authorization}
 * header is omitted, which is exactly what a local Ollama expects. No automatic retries; failures
 * propagate as SPI exceptions for the worker to classify.
 */
public final class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final String providerId;
    private final HttpClient httpClient;
    private final OpenAiMessageMapper messageMapper;
    private final OpenAiResponseMapper responseMapper;
    private final OpenAiErrorMapper errorMapper;
    private final String baseUrl;
    private final int defaultMaxTokens;
    private final Duration requestTimeout;

    public OpenAiCompatibleLlmProvider(String providerId,
                                       HttpClient httpClient,
                                       OpenAiMessageMapper messageMapper,
                                       OpenAiResponseMapper responseMapper,
                                       OpenAiErrorMapper errorMapper,
                                       String baseUrl,
                                       int defaultMaxTokens,
                                       Duration requestTimeout) {
        this.providerId = providerId;
        this.httpClient = httpClient;
        this.messageMapper = messageMapper;
        this.responseMapper = responseMapper;
        this.errorMapper = errorMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.defaultMaxTokens = defaultMaxTokens;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public String id() {
        return providerId;
    }

    @Override
    public boolean supports(String modelName) {
        // OpenAI-compatible endpoints serve arbitrary model names (gpt-*, mistral-*, llama*, ...);
        // routing to the right endpoint is by provider id via the registry, not by model prefix.
        return modelName != null && !modelName.isBlank();
    }

    @Override
    public LlmResponse invoke(LlmInvocation invocation) {
        String model = invocation.modelName();

        String requestBody;
        try {
            requestBody = messageMapper.toRequestJson(invocation, defaultMaxTokens);
        } catch (JsonProcessingException e) {
            throw new LlmProviderException(providerId, model, "Failed to serialize request", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + CHAT_COMPLETIONS_PATH))
                .timeout(requestTimeout)
                .header("content-type", "application/json");

        LlmCredential credential = invocation.credential();
        if (credential != null) {
            String apiKey = credential.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            credential.getOrganizationId()
                    .ifPresent(org -> builder.header("OpenAI-Organization", org));
        }

        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LlmTimeoutException(providerId, model, "Request timed out after " + requestTimeout, e);
        } catch (IOException e) {
            throw new LlmProviderException(providerId, model, "HTTP call to " + providerId + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException(providerId, model, "Interrupted during " + providerId + " call", e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            try {
                return responseMapper.toDomain(response.body());
            } catch (JsonProcessingException e) {
                throw new LlmProviderException(providerId, model, "Failed to parse response", e);
            }
        }
        throw errorMapper.toException(status, response.body(), providerId, model);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
