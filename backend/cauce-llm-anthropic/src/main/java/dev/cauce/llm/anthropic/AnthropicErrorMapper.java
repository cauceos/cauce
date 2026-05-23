package dev.cauce.llm.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmRateLimitException;

/**
 * Maps an Anthropic Messages API error response (HTTP status + body) to the matching SPI
 * exception. Best-effort: the {@code error.message} field is extracted when present, but a
 * malformed or empty body never prevents producing an exception.
 */
final class AnthropicErrorMapper {

    private final ObjectMapper objectMapper;

    AnthropicErrorMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    LlmProviderException toException(int status, String body, String providerId, String modelName) {
        String detail = extractMessage(body);
        String message = detail == null ? "HTTP " + status : "HTTP " + status + ": " + detail;

        return switch (status) {
            case 401, 403 -> new LlmAuthenticationException(providerId, modelName, message);
            case 429 -> new LlmRateLimitException(providerId, modelName, message);
            case 400, 404, 422 -> new LlmInvalidRequestException(providerId, modelName, message);
            default -> new LlmProviderException(providerId, modelName, message);
        };
    }

    private String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            AnthropicErrorEnvelope envelope = objectMapper.readValue(body, AnthropicErrorEnvelope.class);
            return envelope.error() == null ? null : envelope.error().message();
        } catch (Exception e) {
            return null; // best-effort: fall back to the HTTP status only
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnthropicErrorEnvelope(
            @JsonProperty("type") String type,
            @JsonProperty("error") AnthropicError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnthropicError(
            @JsonProperty("type") String type,
            @JsonProperty("message") String message) {
    }
}
