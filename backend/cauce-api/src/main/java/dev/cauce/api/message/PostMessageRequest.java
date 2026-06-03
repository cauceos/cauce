package dev.cauce.api.message;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /v1/agents/{agentId}/messages}: an inbound USER message.
 *
 * <p>The channel is not part of the body — the REST endpoint stamps the reserved built-in
 * {@code "api"} channel server-side. The caller supplies only the external user reference
 * within that channel and the message text. Serialised in snake_case
 * ({@code external_identity_ref}, {@code content}) by the global Jackson naming strategy.
 */
public record PostMessageRequest(
        @NotBlank String externalIdentityRef,
        @NotBlank String content) {
}
