package dev.cauce.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmRateLimitException;

/**
 * Maps an OpenAI-compatible error response (HTTP status + body) to the matching SPI exception.
 * Best-effort message extraction: handles both the OpenAI shape ({@code {"error":{"message":…}}})
 * and the Ollama shape ({@code {"error":"…"}}); a malformed or empty body never prevents producing
 * an exception. The HTTP status drives the exception type, which in turn drives the worker's
 * transient/permanent retry decision.
 */
final class OpenAiErrorMapper {

    private final ObjectMapper objectMapper;

    OpenAiErrorMapper(ObjectMapper objectMapper) {
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
            JsonNode error = objectMapper.readTree(body).get("error");
            if (error == null || error.isNull()) {
                return null;
            }
            if (error.isTextual()) {
                return error.asText(); // Ollama: {"error":"..."}
            }
            JsonNode messageNode = error.get("message"); // OpenAI: {"error":{"message":"..."}}
            return messageNode == null ? null : messageNode.asText();
        } catch (Exception e) {
            return null; // best-effort: fall back to the HTTP status only
        }
    }
}
